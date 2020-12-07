import groovy.json.JsonSlurperClassic 

@NonCPS
def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

def getGitBranches(repo, accessToken) {
    return jsonParse(sh(script: "curl -H 'Authorization: token ${accessToken}' https://api.github.com/repos/${repo}/branches", returnStdout: true))
}

pipeline {
    agent any
    
    tools {nodejs "nodejs-14.11.0"}
    
    environment {
        GIT_REPO = '...'
        GIT_ACCESS_TOKEN = '...'
        GIT_BOT_EMAIL = '...'
        GIT_BOT_NAME = '...'
    }
  
    stages {
        stage('Input') {
            steps {
                script {
                    def branches = getGitBranches(env.GIT_REPO, env.GIT_ACCESS_TOKEN)
                    
                    // there is an error
                    if (branches instanceof HashMap) {
                        currentBuild.result = 'ABORTED'
                        error('Error while receiving branch list. '+branches.message)
                    }
                    
                    env.TARGET_BRANCH = input message: 'Please, select target branch to make a release from', ok: 'Select', parameters: [choice(name: 'Branch', choices: (branches.collect {branch -> return branch.name}))]
                    env.TARGET_VERSION = input message: 'Please, select target version', ok: 'Select', parameters: [choice(name: 'Version', choices: 'patch\nminor\nmajor')]
                }
            }
        }
        
        stage('Git') {
            steps {
                git "git@github.com:${GIT_REPO}.git"
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
     
        stage('Run tests') {
            steps {
                sh 'npm run eslint'
                sh 'npm run jest'
            }
        }

        stage('Version patch') {
            steps {
                sh '''
                    version=$(npm version ${TARGET_VERSION} --no-git-tag-version --force)

                    git config --global user.name "${GIT_BOT_NAME}"
                    git config --global user.email "${GIT_BOT_EMAIL}"
                    git add .
                    git commit -m "${version}"
                    git push --set-upstream origin ${TARGET_BRANCH}

                    curl -H "Authorization: token ${GIT_ACCESS_TOKEN}" -d '{\
                        "tag_name": "'${version}'",
                        "target_commitish": "'${TARGET_BRANCH}'",
                        "name": "'${version}'",
                        "body": "Release of version '${version}'",
                        "draft": false,
                        "prerelease": false\
                    }' https://api.github.com/repos/${GIT_REPO}/releases
                '''
            }
        }
    }
}
