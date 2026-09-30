def call(Map config = [:]) {
    def serviceName = config.serviceName ?: error('Falta serviceName')
    def nexusHost = config.nexusHost ?: error('Falta nexusHost')

    def sourceTag = config.sourceTag ?: error('Falta sourceTag')
    def pullPort = config.pullPort ?: '8083'
    def pushPort = config.pushPort ?: '8082'

    def deployHost = config.deployHost
    def deployUser = config.deployUser ?: 'ubuntu'
    def deployDir = config.deployDir ?: '/home/ubuntu/deploy-registry'

    def composeService = config.composeService ?: serviceName
    def healthUrl = config.healthUrl
    def smokeTestUrl = config.smokeTestUrl
    def runSmokeTests = config.runSmokeTests != false

    def sshCredentialsId = config.sshCredentialsId ?: 'ssh-deploy-qa'
    def nexusCredentialsId = config.nexusCredentialsId ?: 'nexus-credentials'

    def buildDocker = config.buildDocker == true
    def dockerContext = config.dockerContext ?: '.'

    pipeline {
        agent any

        options {
            timestamps()
            disableConcurrentBuilds()
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Prepare Image Tags') {
                steps {
                    script {
                        env.GIT_SHORT = sh(
                            script: 'git rev-parse --short=7 HEAD',
                            returnStdout: true
                        ).trim()

                        env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_SHORT}"

                        env.NEXUS_PULL = "${nexusHost}:${pullPort}"
                        env.NEXUS_PUSH = "${nexusHost}:${pushPort}"

                        env.SOURCE_IMAGE =
                            "${env.NEXUS_PULL}/ingesoft/${serviceName}:${sourceTag}"

                        env.BUILD_IMAGE =
                            "${env.NEXUS_PULL}/ingesoft/${serviceName}:${env.IMAGE_TAG}"

                        env.TARGET_IMAGE =
                            "${env.NEXUS_PUSH}/ingesoft/${serviceName}:${env.IMAGE_TAG}"
                    }

                    echo "Imagen origen configurada: ${env.SOURCE_IMAGE}"
                    echo "Imagen de build: ${env.BUILD_IMAGE}"
                    echo "Imagen promovida: ${env.TARGET_IMAGE}"
                }
            }

            stage('Build Docker Image') {
                when {
                    expression { return buildDocker }
                }

                steps {
                    sh """
                        set -eu
                        docker build --no-cache \
                          -t "${env.BUILD_IMAGE}" \
                          "${dockerContext}"
                    """
                }
            }

            stage('Push Build Image to Nexus') {
                when {
                    expression { return buildDocker }
                }

                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: nexusCredentialsId,
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS'
                        )
                    ]) {
                        sh '''
                            set -eu

                            echo "$NEXUS_PASS" | docker login "$NEXUS_PULL" \
                              -u "$NEXUS_USER" \
                              --password-stdin

                            docker push "$BUILD_IMAGE"

                            docker logout "$NEXUS_PULL" || true
                        '''
                    }
                }
            }

            stage('Promote Docker Image') {
                when {
                    expression {
                        return !buildDocker
                    }
                }

                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: nexusCredentialsId,
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS'
                        )
                    ]) {
                        sh '''
                            set -eu

                            echo "$NEXUS_PASS" | docker login "$NEXUS_PULL" \
                              -u "$NEXUS_USER" \
                              --password-stdin

                            echo "$NEXUS_PASS" | docker login "$NEXUS_PUSH" \
                              -u "$NEXUS_USER" \
                              --password-stdin

                            docker pull "$SOURCE_IMAGE"
                            docker tag "$SOURCE_IMAGE" "$TARGET_IMAGE"
                            docker push "$TARGET_IMAGE"

                            docker logout "$NEXUS_PULL" || true
                            docker logout "$NEXUS_PUSH" || true
                        '''
                    }
                }
            }

            
            stage('Deploy to EC2') {
                when {
                    expression { return deployHost != null }
                }

                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: nexusCredentialsId,
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS'
                        )
                    ]) {
                        sshagent(credentials: [sshCredentialsId]) {
                            sh """
                                set -eu

                                printf '%s' "\$NEXUS_PASS" | ssh \
                                  -o StrictHostKeyChecking=no \
                                  ${deployUser}@${deployHost} \
                                  "set -eu
                                   cd ${deployDir}

                                   docker login ${nexusHost}:${pushPort} \
                                     -u '\$NEXUS_USER' \
                                     --password-stdin

                                   sed -Ei 's|image: ${nexusHost}:[0-9]+/ingesoft/${serviceName}:.*|image: ${nexusHost}:${pushPort}/ingesoft/${serviceName}:${env.IMAGE_TAG}|' docker-compose.yml

                                   docker compose pull ${composeService}
                                   docker compose up -d --no-deps ${composeService}

                                   docker logout ${nexusHost}:${pushPort} || true
                                  "
                            """
                        }
                    }
                }
            }

            stage('Wait for Health') {
                when {
                    expression { return healthUrl != null && deployHost != null }
                }

                steps {
                    sh """
                        set -eu

                        for attempt in \$(seq 1 24); do
                          if curl -fsS --max-time 5 "${healthUrl}" >/dev/null; then
                            echo "Health check aprobado: ${healthUrl}"
                            exit 0
                          fi

                          echo "Esperando health check (\$attempt/24)..."
                          sleep 5
                        done

                        echo "El servicio no estuvo saludable tras 120 segundos."
                        exit 1
                    """
                }
            }

            stage('Smoke Test') {
                when {
                    allOf {
                        expression { return runSmokeTests }
                        expression { return smokeTestUrl != null }
                        expression { return deployHost != null }
                    }
                }

                steps {
                    sh """
                        set -eu
                        curl -fsS --max-time 10 "${smokeTestUrl}" >/dev/null
                        echo "Smoke test aprobado: ${smokeTestUrl}"
                    """
                }
            }
        }
    }
}