def call(Map config = [:]) {
    def serviceName = config.serviceName ?: error("Falta serviceName")
    def nexusHost = config.nexusHost ?: error("Falta nexusHost")
    def sourceTag = config.sourceTag ?: "1.0.0"
    def pullPort = config.pullPort ?: "8083"
    def pushPort = config.pushPort ?: "8082"
    def publishDocker = config.publishDocker != false
    def deployHost = config.deployHost
    def deployUser = config.deployUser
    def deployDir = config.deployDir ?: "/home/ubuntu/deploy-registry"
    def runSmokeTests = config.runSmokeTests != false
    def smokeTestUrl = config.smokeTestUrl ?: "http://${deployHost}:80/api/products"

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

            stage('Prepare Image Tag') {
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

                        env.TARGET_IMAGE =
                            "${env.NEXUS_PUSH}/ingesoft/${serviceName}:${env.IMAGE_TAG}"
                    }

                    echo "Origen: ${env.SOURCE_IMAGE}"
                    echo "Destino: ${env.TARGET_IMAGE}"
                }
            }

            stage('Promote Docker Image') {
                when {
                    expression { return publishDocker }
                }

                steps {
                    withCredentials([
                        usernamePassword(
                            credentialsId: 'nexus-credentials',
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS'
                        )
                    ]) {
                        sh '''
                            set -eu

                            echo "$NEXUS_PASS" | docker login \
                              "$NEXUS_PULL" \
                              -u "$NEXUS_USER" \
                              --password-stdin

                            echo "$NEXUS_PASS" | docker login \
                              "$NEXUS_PUSH" \
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
                            credentialsId: 'nexus-credentials',
                            usernameVariable: 'NEXUS_USER',
                            passwordVariable: 'NEXUS_PASS'
                        )
                    ]) {
                        sshagent(credentials: ['deploy-ssh-key']) {
                            sh """
                                set -eu

                                printf '%s' "\$NEXUS_PASS" | ssh \
                                  -o StrictHostKeyChecking=no \
                                  ${deployUser}@${deployHost} \
                                  "set -eu
                                   cd ${deployDir}

                                   printf '%s' '\$(cat)' | docker login ${nexusHost}:${pushPort} \
                                     -u '\$NEXUS_USER' \
                                     --password-stdin

                                   sed -i 's|${nexusHost}:${pullPort}/ingesoft/${serviceName}:.*|${nexusHost}:${pushPort}/ingesoft/${serviceName}:${env.IMAGE_TAG}|g' docker-compose.yml

                                   docker compose pull
                                   docker compose up -d --remove-orphans
                                   docker logout ${nexusHost}:${pushPort} || true
                                  "
                            """
                        }
                    }
                }
            }

            stage('Wait for Backend Health') {
                when {
                    expression { return deployHost != null }
                }

                steps {
                    sh """
                        set -eu

                        for attempt in \$(seq 1 24); do
                          if curl -fsS --max-time 5 \
                            http://${deployHost}/api/products >/dev/null; then
                            echo "Backend saludable."
                            exit 0
                          fi

                          echo "Esperando backend (intento \$attempt/24)..."
                          sleep 5
                        done

                        echo "El backend no respondió dentro de 120 segundos."
                        exit 1
                    """
                }
            }

            stage('Smoke Test') {
                when {
                    allOf {
                        expression { return deployHost != null }
                        expression { return runSmokeTests }
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