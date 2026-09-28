def call(Map config = [:]) {
    pipeline {
        agent any

        stages {
            stage('Shared Library Check') {
                steps {
                    echo "Shared Library cargada correctamente"
                    echo "Servicio: ${config.serviceName ?: 'sin-nombre'}"
                }
            }
        }
    }
}
