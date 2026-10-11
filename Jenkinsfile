// Separate working directories keep compilation, Surefire reports and test-run.log
// independent. Docker images are built once; Testcontainers allocates each JVM's stack.
def runTestBranch(String branch, String tests) {
    dir("ci-tests/${branch}") {
        deleteDir()
        unstash 'test-sources'
        withEnv(["TEST_SELECTION=${tests}", "RESOURCES_FILE=${pwd()}/ci-logs/test-resources.txt"]) {
            sh label: 'Run Maven tests', script: '''
                mkdir -p ci-logs
                (
                    set +x
                    free -m | head -n 1
                    while true; do
                        echo "$(date +%T) $(free -m | sed -n 2p) load $(cut -d ' ' -f 1-3 /proc/loadavg)"
                        sleep 30
                    done
                ) > "$RESOURCES_FILE" 2>&1 &
                MONITOR_PID=$!
                trap 'kill "$MONITOR_PID" 2>/dev/null || true' 0
                mvn -B clean install -Dapi.version=1.43 -Dtest="$TEST_SELECTION" \
                    -Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav
            '''
        }
    }
}

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
                stash name: 'test-sources', includes: 'pom.xml,src/**'
            }
        }

        stage('Parallel tests') {
            failFast true
            parallel {
                stage('Tests: sabrev4_7/cal') {
                    steps {
                        runTestBranch('cal', 'com/linagora/dav/sabrev4_7/cal/**')
                    }
                }
                stage('Tests: general') {
                    steps {
                        // Keep Surefire's default test naming patterns, excluding only the calendar group.
                        runTestBranch('other', '**/Test*,**/*Test,**/*Tests,**/*TestCase,!com/linagora/dav/sabrev4_7/cal/**')
                    }
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'ci-tests/*/ci-logs/test-resources.txt', allowEmptyArchive: true, fingerprint: false
                    junit(testResults: 'ci-tests/*/target/surefire-reports/*.xml', allowEmptyResults: false)
                }
                failure {
                    archiveArtifacts artifacts: 'ci-tests/*/target/test-run.log', fingerprint: true
                    archiveArtifacts artifacts: 'ci-tests/*/target/surefire-reports/*', fingerprint: true
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