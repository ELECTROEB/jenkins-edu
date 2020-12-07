import groovy.json.JsonSlurperClassic 

@NonCPS
def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

def getGitTags(repo, accessToken) {
    return jsonParse(sh(script: "curl -H 'Authorization: token ${accessToken}' https://api.github.com/repos/${repo}/git/refs/tags", returnStdout: true))
}

pipeline {
    agent any
    
    tools {nodejs "nodejs-14.11.0"}
    
    environment {
        GIT_REPO = '...'
        GIT_ACCESS_TOKEN = '...'
        GIT_BOT_EMAIL = '...'
        GIT_BOT_NAME = '...'
        APP_URL = '...'
    }
    
    stages {
        stage('Input') {
            steps {
                script {
                    def tags = getGitTags(env.GIT_REPO, env.GIT_ACCESS_TOKEN)
                    
                    // there is an error
                    if (tags instanceof HashMap) {
                        currentBuild.result = 'ABORTED'
                        error('Error while receiving tags list. '+tags.message)
                    }

                    env.TARGET_ENV = input message: 'Please, select target environment', ok: 'Select', parameters: [choice(name: 'Environment', choices: 'DEV\nPROD')]
                    env.TARGET_TAG = input message: 'Please, select target github release', ok: 'Select', parameters: [choice(name: 'Release', choices: (tags.collect {tag -> return tag.ref.tokenize('/')[2]}).reverse().join('\n'))]
                    env.TARGET_APP_URL = env.TARGET_ENV == 'DEV' ? "dev.${APP_URL}" : "${APP_URL}"
                }

                echo "${env.TARGET_ENV}"
                echo "${env.TARGET_TAG}"
                echo "${env.TARGET_APP_URL}"
            }
        }
        
        stage('Git') {
            steps {
                checkout scm: [$class: 'GitSCM', userRemoteConfigs: [[url: "git@github.com:${GIT_REPO}.git", credentialsId: '...' ]], branches: [[name: "refs/tags/${TARGET_TAG}"]]], poll: false
            }
        }
    
        stage('Install') {
            steps {
                sh '''
                    if [ -f "package-lock.json" ]; then
                        npm ci
                    else 
                        npm install
                    fi
                '''
            }
        }  

        stage('Build') {
            steps {
                script {
                    if (env.TARGET_ENV == 'DEV') {
                        sh 'npm run build:dev'
                    } else {
                        sh 'npm run build'
                    }
                }
            }
        }
        
        stage('Deploy') {
            steps {
                sshPublisher(
                    continueOnError: false, failOnError: true,
                    publishers: [
                        sshPublisherDesc(
                            configName: '...',
                            verbose: true,
                            transfers: [
                                sshTransfer(
                                    sourceFiles: 'dist/**/*',
                                    removePrefix: 'dist',
                                    remoteDirectory: "${TARGET_APP_URL}",
                                )
                            ]
                        )
                    ]
                )
            }
        }
    }
}
