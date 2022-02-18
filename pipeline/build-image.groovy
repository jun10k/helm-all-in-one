@Library('sonar@master') _
pipeline {
    parameters {
        string(name: 'branch', defaultValue: '', description: '代码分支')
        string(name: 'version', defaultValue: '', description: '服务tag')
        string(name: 'name', defaultValue: '', description: '服务名称')
        string(name: 'repo', defaultValue: '', description: '仓库地址')
        string(name: 'repo_dir', defaultValue: '', description: '仓库下项目目录')
        string(name: 'build_tool', defaultValue: 'gradle', description: 'gradle or sbt')
        string(name: 'git_platform', defaultValue: 'github', description: 'gitlab or github')
        booleanParam(name: 'force_test', defaultValue: false, description: 'Run test and fail build on test fail')
        booleanParam(name: 'sonar_test', defaultValue: false, description: 'Code Quality and Code Security')
    }
    agent any
    options {
        timeout(time: 20, unit: 'MINUTES')
        gitLabConnection('gitlab_xsio')
        disableResume()
    }
    stages {
        stage('Cloning Git') {
            steps {
                script {
                    if (repo_dir == 'null') {
                        repo_dir == ""
                    }
                    if (build_tool == 'null') {
                        build_tool = 'gradle'
                    }
                    if (repo ==~ 'adhub/.*' || repo ==~ 'AdHub/.*') {
                        registry = "nexus-release.xsio.cn/adhub"
                        dockerImage = "${registry}/${name}:${version}"
                        imageLatest = "${registry}/${name}"
                    } else {
                        registry = "nexus-release.xsio.cn/${branch}"
                        dockerImage = "${registry}/${name}:${version}"
                        imageLatest = "${registry}/${name}"
                    }
                    acr = "registry-vpc.cn-hangzhou.aliyuncs.com/xsio"
                    acrDockerImage = "${acr}/${name}:${version}"
                    acrImageLatest = "${acr}/${name}:${branch}-latest"
                    repo1 = repo.replace("%2F", "/")
                    dir = repo1.split('/')[-1]
                    parentBranch = branch.split('-')[0]
                    if (git_platform == 'gitlab') {
                        sh """
                          rm -rf ${dir}
                          git clone -b ${branch} --depth 1 ssh://git@gitlab.cd.xsio.cn:22222/${repo}.git
                          cd ${dir}
                          git log
                        """
                    } else if (git_platform == 'github'){
                        sh """
                          rm -rf ${dir}
                          git clone -b ${branch} --depth 1 git@github.com:${repo1}
                          cd ${dir}
                          git log
                        """
                    }
                }
            }
        }
        stage('Sonar Check') {
            when {
                expression { params.sonar_test == true }
            }
            steps {
                script{
                    env.NODE_HOME="/var/jenkins_home/tools/node-v12.22.1-linux-x64"
                    env.JAVA_HOME="/var/jenkins_home/tools/jdk-11.0.12"
                    env.CLASSPATH=".:$JAVA_HOME/lib/dt.jar:$JAVA_HOME/lib/tools.jar:$CLASSPATH"
                    env.PATH=".:$JAVA_HOME/bin:$NODE_HOME/bin:$PATH"
                    updateGitlabCommitStatus name: 'Sonar Check', state: 'running'
                    try{
                        sonar_check.byBranch("${name}","${branch}","${GIT_COMMIT}")
                    }catch(Exception ex){
                        updateGitlabCommitStatus name: 'Sonar Check', state: 'failed'
                        throw ex;
                    }
                    updateGitlabCommitStatus name: 'Sonar Check', state: 'success'
                }
            }
        }
        stage('build') {
            steps{
                script {
                    updateGitlabCommitStatus name: 'Sonar Check', state: 'running'
                    try{
                        if (build_tool == 'gradle') {
                            if(name=='extduiba') {
                                sh """
                                    . /root/.bashrc
                                    cd ${dir}/src/main/webapp
                                    rm -rf dist
                                    npm i
                                    npm run build
                                """
                                if (!fileExists("node_modules")) {
                                    sh "rm -rf node_modules"
                                }
                                sh "cd -"
                            } else if (name == 'opt-board') {
                                if (!fileExists("${dir}/src/main/webapp")) {
                                    sh "mkdir -p ${dir}/src/main/webapp"
                                }
                                if (fileExists("${dir}/ui")) {
                                    sh """
                                        . /root/.bashrc
                                        cd ${dir}/ui
                                        rm -rf ../src/main/webapp/*
                                        npm install
                                        npm run build
                                        [ -d ../src/main/webapp/dist ] || mv dist ../src/main/webapp/
                                        ls ../src/main/webapp/
                                        cd -
                                    """
                                }
                            } else if (name == 'authnz') {
                                sh """
                                        . /root/.bashrc
                                        cd ${dir}/portal-ui
                                        npm install
                                        npm run build
                                        cd ..
                                        rm -rf src/main/resources/public
                                        cp -r portal-ui/build src/main/resources/public
                                        export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                        ./gradlew --no-daemon clean bootRepackage  -Dversion=${version} -Dbranch=${parentBranch?:""}
                                    """
                            } else if(name == 'extappendix' || name == 'extdatadmconnector' ){
                                sh """
                                    . /root/.bashrc
                                    cd ${dir}
                                    cd ./ui
                                    npm i
                                    npm i -f @convertlab/c-design @convertlab/uilib @convertlab/ui-common
                                    npm run build
                                    ls ../src/main/webapp/dist
                                    cd -
                                """
                            } else if(name == 'extwechatwork' ){
                                sh """
                                    . /root/.bashrc
                                    cd ${dir}
                                    cd ./ui
                                    rm -rf node_modules package-lock.json
                                    node -v
                                    yarn install
                                    yarn run build
                                """
                            } else if(name == 'extwebinar'){
                                sh """
                                    . /root/.bashrc
                                    cd ${dir}/ui
                                    npm install
                                    NODE_ENV=production npm run build
                                    cd -
                        
                                    cd ${dir}/ui-h5
                                    npm install
                                    NODE_ENV=production npm run build
                                    cd -
                                """
                            }else if(name == 'extbaiduapp'){
                                sh """
                                    . /root/.bashrc
                                    cd ${dir}/ui

                                    if [ -z "\$(command -v python &> /dev/null)" ]; then
                                        echo "python not installed"
                                        apt-get install -y python
                                    else
                                        echo "python installed"  
                                    fi
                                    python build.py jenkins
                                    cd -
                                """
                            }else if(name == 'ext-tracking-ana'){
                                sh """
                                    . /root/.bashrc
                                    cd ${dir}/ui

                                    if [ -z "\$(command -v python &> /dev/null)" ]; then
                                        echo "python not installed"
                                        apt-get install -y python
                                    else
                                        echo "python installed"  
                                    fi
                                    python build.py jenkins
                                    cd -
                                """
                            }else if(name ==~ /ext.*/ || name == 'employeert' || name == 'cpjinshuju'){
                                if (!fileExists("${dir}/src/main/webapp")) {
                                    sh "mkdir -p ${dir}/src/main/webapp"
                                }
                                if (fileExists("${dir}/ui")) {
                                    sh """
                                        . /root/.bashrc
                                        cd ${dir}/ui
                                        rm -rf ../src/main/webapp/*
                                        npm install
                                        npm i -f \$(node -p "Object.keys(require('./package.json').dependencies).filter(package=>package.startsWith('@convertlab/')).join(' ')") --no-save
                                        NODE_ENV=production npm run build
                                        [ -d ../src/main/webapp/dist ] || mv dist ../src/main/webapp/
                                        ls ../src/main/webapp/
                                        cd -
                                    """
                                }
                            }
                            if (repo_dir) {
                                sh """ 
                                    pwd
                                    cd ${dir}/${repo_dir}
                                    . /root/.bashrc
                                    . /root/add_gradle_build_info.sh
                                    rm -rf build
                                    export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                    ./gradlew --no-daemon clean bootRepackage  -Dversion=${version} -Dbranch=${parentBranch?:""}
                                """
                            } else {
                                if (name == 'extwecomservice' || name == 'extwecomjob') {
                                    sh """
                                        cd ${dir}
                                        . /root/.bashrc
                                        . /root/add_gradle_build_info.sh
                                        rm -rf build
                                        export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                        ./gradlew --no-daemon clean bootWar -Dversion=${version} -Dbranch=${parentBranch?:""} 
                                    """
                                }else if(name == "extbaiduapp"){
                                    sh """
                                        cd ${dir}
                                        . /root/.bashrc
                                        . /root/add_gradle_build_info.sh
                                        rm -rf build
                                        export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                        ./gradlew --no-daemon clean bootJar -Dversion=${version} -Dbranch=${parentBranch?:""} 
                                    """
                                } else if(name == "ext-tracking-ana"){
                                    sh """
                                        cd ${dir}
                                        . /root/.bashrc
                                        . /root/add_gradle_build_info.sh
                                        rm -rf build
                                        export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                        ./gradlew --no-daemon clean bootJar -Dversion=${version} -Dbranch=${parentBranch?:""} 
                                    """
                                } else {
                                    sh """
                                        cd ${dir}
                                        . /root/.bashrc
                                        . /root/add_gradle_build_info.sh
                                        rm -rf build
                                        export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                        ./gradlew --no-daemon clean bootRepackage  -Dversion=${version} -Dbranch=${parentBranch?:""} 
                                    """
                                }
                            }
                            sh """
                                # archive war to nexus
                                cd ${dir}/${repo_dir}
                                # /var/jenkins_home/tools/maven/bin/mvn -B deploy:deploy-file -DgroupId=com.convertlab -DartifactId=${name} -Dversion=${version} -Dpackaging=jar -Dfile=`find build/libs -name "*.war" | head -1` -Durl=http://nexus.xsio.cn/repository/maven-archive/ -DrepositoryId=xsio-archive || true
                            """
                        } else if (build_tool == 'sbt') {
                            sh """ 
                                cd ${repo_dir?dir+'/'+repo_dir:dir}
                                ./sbt -Dsbt.log.noformat=true clean package 
                            """
                        } else if (build_tool == 'mvn') {
                            sh """
                              cd ${dir}
                              . /root/.bashrc
                              /var/jenkins_home/tools/maven/bin/mvn -V -B clean package
                            """
                        } else if (build_tool == 'gradle_app') {
                            sh """
                                cd ${dir}
                                . /root/.bashrc
                                . /root/add_gradle_build_info.sh
                                rm -rf build
                                export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                ./gradlew --no-daemon clean build -Dversion=${version} -Dbranch=${parentBranch?:""} 
                            """
                        } else if (build_tool == 'npm') {
                            sh """
                                 . /root/.bashrc
                                 cd ${dir}
                                 if [ -f "yarn.lock" ]; then
                                 	yarn install && yarn build 
                                 else
                                 	npm install --unsafe-perm && npm run build
                                 fi
                            """
                        } else if (build_tool == 'php') {
                            sh """
                                 . /root/.bashrc
                                 cd ${dir}
                            """
                        } else if (build_tool == 'make') {
                            sh """
                             . /root/.bashrc
                             cd ${dir}
                             make
                           """
                        } else if (build_tool == 'docker') {
                            sh """
                             . /root/.bashrc
                             cd ${dir}
                             echo 'skip to docker build'
                           """
                        } else if(name == 'apidocsv2' && build_tool== 'docker') {
                            sh """
                                pwd
                                cd ${dir}
                                rm -rf node_modules
                                rm -f package-lock.json
                            """
                        }
                    }catch(Exception ex){
                        updateGitlabCommitStatus name: 'Sonar Check', state: 'failed'
                        throw ex;
                    }
                    updateGitlabCommitStatus name: 'Sonar Check', state: 'success'
                }
            }
        }
        stage('Security Scan') {
            steps {
                script {
                    sh """ 
                            cd ${repo_dir ? dir + '/' + repo_dir : dir}
                            trivy fs --skip-update --severity=CRITICAL,HIGH -f json -o results.json . || true
                     """
                    recordIssues(tools: [trivy(pattern: "${repo_dir ? dir + '/' + repo_dir : dir}/results.json")])
                }
            }
        }
        stage('Test') {
            when {
                expression { params.force_test == true }
            }
            steps {
                script {
                    updateGitlabCommitStatus name: 'job build', state: 'running'
                    try{
                        if (build_tool == 'gradle') {
                            sh """ 
                                pwd
                                cd ${repo_dir?dir+'/'+repo_dir:dir}
                                . /root/.bashrc
                                . /root/add_gradle_build_info.sh
                                export GRADLE_OPTS="-Dfile.encoding=utf-8"
                                ./gradlew --no-daemon testClasses -Dversion=${version} -Dbranch=${parentBranch?:""} &&
                                    ( ./gradlew --no-daemon test -Dversion=${version} -Dbranch=${parentBranch?:""} || true )
                              """
                            junit testResults: """${repo_dir?dir+'/'+repo_dir:dir}/build/test-results/**/*.xml""", allowEmptyResults: true
                        } else if (build_tool == 'sbt') {
                            sh """ 
                            cd ${repo_dir?dir+'/'+repo_dir:dir}
                            ./sbt -Dsbt.log.noformat=true "Test/compile" &&
                                (test_timefactor=2 ./sbt -Dsbt.log.noformat=true test || true)
                            """
                            junit testResults: """${repo_dir?dir+'/'+repo_dir:dir}/target/test-reports/*.xml""", allowEmptyResults: true
                        }
                    }catch(Exception ex){
                        updateGitlabCommitStatus name: 'job build', state: 'failed'
                        throw ex;
                    }
                    updateGitlabCommitStatus name: 'job build', state: 'success'
                }
            }
        }
        stage('Building image') {
            steps{
                script {
                    updateGitlabCommitStatus name: 'Building image', state: 'running'
                    try{
                        if (branch ==~ '.*-guangda'){
                            pwd
                            if (name == 'flowstat' || name == 'queryhub' || name == 'maxwell' || name == 'apiv2' || name == 'collector-entry' || name == 'reactflow2' || name == 'kafka2es' || name == 'impala') {
                                pwd
                            }else{
                                sh """
                                    rm -rf ~/guangda/${name}
                                    mkdir -p ~/guangda/${name}
                                    if [ ! ${repo_dir} ];then
                                        cp ${dir}/build/libs/*-latest.war ~/guangda/${name}/
                                    elif [ ${repo_dir} == "/" ];then
                                        cp ${dir}/build/libs/*-latest.war ~/guangda/${name}/
                                    else
                                        cp -a ${dir}/${repo_dir}/build/libs/*-latest.war ~/guangda/${name}/
                                    fi
                                    ls ~/guangda/${name}
                                """
                            }
                        }
                        sh """
                          cd ${dir}/${repo_dir}
                          sed -i 's/8u221v1/8u202/g' Dockerfile
                          docker build -t ${dockerImage} . 
                          docker tag ${dockerImage} ${imageLatest}  
                        """
                    }catch(Exception ex){
                        updateGitlabCommitStatus name: 'Building image', state: 'failed'
                        throw ex;
                    }
                    updateGitlabCommitStatus name: 'Building image', state: 'success'
                }
            }
        }
        stage('Push Image') {
            steps{
                script {
                    updateGitlabCommitStatus name: 'Push Image', state: 'running'
                    try{
                        docker.withRegistry( 'http://nexus-release.xsio.cn', 'nexus' ) {
                            sh """
                                docker push ${dockerImage}
                                docker push ${imageLatest}
                              """
                        }
                        docker.withRegistry( 'https://registry-vpc.cn-hangzhou.aliyuncs.com', 'acr' ) {
                            sh """
                                docker tag ${dockerImage} ${acrDockerImage}
                                docker push ${acrDockerImage} || echo true
                                docker tag ${dockerImage} ${acrImageLatest}
                                docker push ${acrImageLatest} || echo true
                                docker rmi ${acrDockerImage} ${acrImageLatest} ${dockerImage} ${imageLatest}
                              """
                        }
                    }catch(Exception ex){
                        updateGitlabCommitStatus name: 'Push Image', state: 'failed'
                        throw ex;
                    }
                    updateGitlabCommitStatus name: 'Push Image', state: 'success'
                }
            }
        }
        stage('tag') {
            steps {
                sh """
              cd ${dir}
              if git tag ${VERSION} &&  git push  --tags >/dev/null 2>&1; then
                echo "tag push succeed";
              else
                echo "tag push failed"
              fi
          """
            }
        }
    }
}
