pipeline {
    agent any

    environment {
        BACKEND_DIR   = "PaymentProcessing/backend"
        FRONTEND_DIR  = "PaymentProcessing/frontend-react"
        DOCKER_IMAGE_BACKEND  = "payments-backend"
        DOCKER_IMAGE_FRONTEND = "payments-frontend"
        IMAGE_TAG = "${env.BUILD_NUMBER}"
    }

    options {
        timestamps()
        skipDefaultCheckout(false)
    }

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Backend: Build & Test') {
            steps {
                sh """
                    docker run --rm \\
                        -v "\$(pwd)":/workspace \\
                        -v maven-repo-cache:/root/.m2 \\
                        -w /workspace/${BACKEND_DIR} \\
                        maven:3.9-eclipse-temurin-17 \\
                        mvn -B clean verify
                """
            }
            post {
                always {
                    junit testResults: "${BACKEND_DIR}/target/surefire-reports/*.xml", allowEmptyResults: true
                }
            }
        }

        stage('Frontend: Build') {
            steps {
                sh """
                    docker run --rm \\
                        -v "\$(pwd)":/workspace \\
                        -w /workspace/${FRONTEND_DIR} \\
                        node:20-alpine \\
                        sh -c "npm ci && npm run build"
                """
            }
        }

        stage('Docker: Build Images') {
            steps {
                sh "docker build -t ${DOCKER_IMAGE_BACKEND}:${IMAGE_TAG} -t ${DOCKER_IMAGE_BACKEND}:latest ${BACKEND_DIR}"
                sh "docker build -t ${DOCKER_IMAGE_FRONTEND}:${IMAGE_TAG} -t ${DOCKER_IMAGE_FRONTEND}:latest ${FRONTEND_DIR}"
            }
        }

        stage('Docker: Push Images') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'docker-registry-creds', usernameVariable: 'DOCKER_USER', passwordVariable: 'DOCKER_PASS')]) {
                    sh 'echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin'
                    sh "docker push ${DOCKER_IMAGE_BACKEND}:${IMAGE_TAG}"
                    sh "docker push ${DOCKER_IMAGE_BACKEND}:latest"
                    sh "docker push ${DOCKER_IMAGE_FRONTEND}:${IMAGE_TAG}"
                    sh "docker push ${DOCKER_IMAGE_FRONTEND}:latest"
                }
            }
        }

        stage('Deploy: Docker Compose') {
            steps {
                sh 'docker compose up -d --build'
            }
        }
    }

    post {
        success {
            echo "Build ${env.BUILD_NUMBER} completed successfully."
        }
        failure {
            echo "Build ${env.BUILD_NUMBER} failed. Check the stage logs above."
        }
        always {
            sh 'docker logout || true'
        }
    }
}
