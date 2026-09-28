def call(Map config = [:]) {
    def serviceName = config.serviceName ?: error("Falta serviceName")
    def nexusHost = config.nexusHost ?: error("Falta nexusHost")
    def sourceTag = config.sourceTag ?: "1.0.0"
    def pullPort = config.pullPort ?: "8083"
    def pushPort = config.pushPort ?: "8082"
    def publishDocker = config.publishDocker != false

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
        }
    }
}