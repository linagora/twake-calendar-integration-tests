import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def failedTestSummary = ''

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
            script {
                try {
                    if (env.CHANGE_ID && currentBuild.currentResult != 'SUCCESS') {
                        // Read reports before deleteDir; retain only Markdown for the final notification.
                        try {
                            failedTestSummary = sh(label: 'Summarize failed tests', returnStdout: true,
                                script: 'python3 ci/summarize-test-failures.py').trim()
                        } catch (FlowInterruptedException interrupted) {
                            throw interrupted
                        } catch (Exception ignored) {
                            failedTestSummary = '> Failed test details are unavailable. See the build log.'
                            echo 'WARNING: Could not summarize failed tests.'
                        }
                    }
                } finally {
                    deleteDir() /* clean up our workspace even if summary collection is interrupted */
                }
            }
        }
        cleanup {
            script {
                if (env.CHANGE_ID) {
                    // Report the final result after all stages and other post actions.
                    try {
                        def result = currentBuild.currentResult
                        def icons = [SUCCESS: '✅', FAILURE: '❌', UNSTABLE: '⚠️', ABORTED: '⏹️', NOT_BUILT: '⏭️']
                        def body = "${icons[result] ?: 'ℹ️'} Jenkins pipeline finished: **${result}**."
                        if (failedTestSummary) {
                            body += "\n\n${failedTestSummary}"
                        }
                        withEnv(["PR_COMMENT_JSON=${groovy.json.JsonOutput.toJson([body: body])}"]) {
                            withCredentials([string(credentialsId: 'github-calendar-bot', variable: 'GITHUB_TOKEN')]) {
                                def status = sh(label: 'Comment Jenkins result on PR', returnStatus: true, script: '''
                                    set +x
                                    printf '%s' "$PR_COMMENT_JSON" | curl --fail --silent --show-error \\
                                        --connect-timeout 10 --max-time 30 -o /dev/null -X POST \\
                                        -H "Authorization: Bearer $GITHUB_TOKEN" \\
                                        -H "Content-Type: application/json" \\
                                        --data-binary @- \\
                                        "https://api.github.com/repos/linagora/twake-calendar-integration-tests/issues/$CHANGE_ID/comments"
                                ''')
                                if (status != 0) {
                                    echo "WARNING: Could not post Jenkins result to PR (curl exit ${status})."
                                }
                            }
                        }
                    } catch (FlowInterruptedException interrupted) {
                        throw interrupted
                    } catch (Exception ignored) {
                        echo 'WARNING: Could not post Jenkins result to PR.'
                    }
                }
            }
        }
    }
}