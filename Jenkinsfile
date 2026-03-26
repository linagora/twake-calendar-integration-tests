pipeline {
    agent any

    environment {
        DOCKER_HUB_CREDENTIAL = credentials('dockerHub')
    }

    options {
        // Configure an overall timeout for the build.
        timeout(time: 3, unit: 'HOURS')
        disableConcurrentBuilds()
    }

    stages {
        stage('Pre-build') {
            steps {
                sh 'sh pre-build.sh'
            }
        }

        stage('Test default config') {
            steps {
                sh 'mvn clean install -Dapi.version=1.43'
            }
            post {
                always {
                    junit(testResults: '**/surefire-reports/*.xml', allowEmptyResults: false)
                }
                failure {
                    archiveArtifacts artifacts: '**/target/test-run.log', fingerprint: true
                    archiveArtifacts artifacts: '**/surefire-reports/*', fingerprint: true
                }
            }
        }

        stage('Test with amqp scheduling enabled') {
            steps {
                sh 'mvn test -Damqp.scheduling.enabled=true -Dapi.version=1.43 -Dsurefire.reportsDirectory=target/surefire-reports-amqp-scheduling'
            }
            post {
                always {
                    junit(testResults: 'target/surefire-reports-amqp-scheduling/*.xml', allowEmptyResults: false)
                }
                failure {
                    archiveArtifacts artifacts: '**/target/test-run.log', fingerprint: true
                    archiveArtifacts artifacts: 'target/surefire-reports-amqp-scheduling/*', fingerprint: true
                }
            }
        }
    }

    post {
        always {
            deleteDir() /* clean up our workspace */
        }
    }
}