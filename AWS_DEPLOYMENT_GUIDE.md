# AWS Deployment Guide for Chat App

This guide walks you through deploying the chat application to AWS using EKS (Elastic Kubernetes Service) with managed services.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [AWS CLI Setup](#2-aws-cli-setup)
3. [Create ECR Repository](#3-create-ecr-repository)
4. [Build and Push Docker Image](#4-build-and-push-docker-image)
5. [Create EKS Cluster](#5-create-eks-cluster)
6. [Set Up Managed Services](#6-set-up-managed-services)
7. [Update Kubernetes Configurations](#7-update-kubernetes-configurations)
8. [Deploy to EKS](#8-deploy-to-eks)
9. [Configure Ingress](#9-configure-ingress)
10. [Verify Deployment](#10-verify-deployment)
11. [Monitoring & Logging](#11-monitoring--logging)
12. [Cost Optimization Tips](#12-cost-optimization-tips)
13. [Cleanup](#13-cleanup)

---

## 1. Prerequisites

### Required Tools

Install the following tools on your local machine:

```bash
# AWS CLI v2
curl "https://awscli.amazonaws.com/AWSCLIV2.pkg" -o "AWSCLIV2.pkg"
sudo installer -pkg AWSCLIV2.pkg -target /

# kubectl
brew install kubectl

# eksctl
brew tap weaveworks/tap
brew install weaveworks/tap/eksctl

# Docker (if not installed)
brew install --cask docker
```

### AWS Account Requirements

- Active AWS account with billing enabled
- IAM user with the following permissions:
  - `AmazonEKSClusterPolicy`
  - `AmazonEKSWorkerNodePolicy`
  - `AmazonEC2ContainerRegistryFullAccess`
  - `AmazonRDSFullAccess`
  - `AmazonElastiCacheFullAccess`
  - `AmazonMSKFullAccess`
  - `AmazonVPCFullAccess`

---

## 2. AWS CLI Setup

### Configure AWS CLI

```bash
aws configure
```

Enter your credentials:
```
AWS Access Key ID: <your-access-key>
AWS Secret Access Key: <your-secret-key>
Default region name: us-east-1
Default output format: json
```

### Verify Configuration

```bash
aws sts get-caller-identity
```

---

## 3. Create ECR Repository

### Create Repository

```bash
# Set variables
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REPO=chat-app

# Create ECR repository
aws ecr create-repository \
    --repository-name $ECR_REPO \
    --region $AWS_REGION \
    --image-scanning-configuration scanOnPush=true
```

### Authenticate Docker with ECR

```bash
aws ecr get-login-password --region $AWS_REGION | \
    docker login --username AWS --password-stdin $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com
```

---

## 4. Build and Push Docker Image

### Build the Image

```bash
# From project root directory
docker build -t chat-app:latest .
```

### Tag and Push to ECR

```bash
# Tag the image
docker tag chat-app:latest $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest

# Push to ECR
docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest
```

---

## 5. Create EKS Cluster

### Create Cluster with eksctl

```bash
# Create EKS cluster (takes 15-20 minutes)
eksctl create cluster \
    --name chat-app-cluster \
    --region $AWS_REGION \
    --version 1.29 \
    --nodegroup-name chat-app-nodes \
    --node-type t3.medium \
    --nodes 2 \
    --nodes-min 2 \
    --nodes-max 5 \
    --managed
```

### Verify Cluster

```bash
# Update kubeconfig
aws eks update-kubeconfig --name chat-app-cluster --region $AWS_REGION

# Verify connection
kubectl get nodes
```

---

## 6. Set Up Managed Services

### 6.1 Create VPC Security Groups

First, get your VPC and subnet information:

```bash
# Get VPC ID from EKS cluster
export VPC_ID=$(aws eks describe-cluster \
    --name chat-app-cluster \
    --query "cluster.resourcesVpcConfig.vpcId" \
    --output text)

# Get subnet IDs
export SUBNET_IDS=$(aws eks describe-cluster \
    --name chat-app-cluster \
    --query "cluster.resourcesVpcConfig.subnetIds" \
    --output text)

echo "VPC ID: $VPC_ID"
echo "Subnet IDs: $SUBNET_IDS"
```

### 6.2 Create RDS MySQL Database

```bash
# Create DB subnet group
aws rds create-db-subnet-group \
    --db-subnet-group-name chat-app-db-subnet \
    --db-subnet-group-description "Subnet group for chat app RDS" \
    --subnet-ids $SUBNET_IDS

# Create security group for RDS
export RDS_SG_ID=$(aws ec2 create-security-group \
    --group-name chat-app-rds-sg \
    --description "Security group for chat app RDS" \
    --vpc-id $VPC_ID \
    --query 'GroupId' \
    --output text)

# Allow MySQL port from VPC CIDR
export VPC_CIDR=$(aws ec2 describe-vpcs --vpc-ids $VPC_ID --query 'Vpcs[0].CidrBlock' --output text)

aws ec2 authorize-security-group-ingress \
    --group-id $RDS_SG_ID \
    --protocol tcp \
    --port 3306 \
    --cidr $VPC_CIDR

# Create RDS MySQL instance
aws rds create-db-instance \
    --db-instance-identifier chat-app-mysql \
    --db-instance-class db.t3.small \
    --engine mysql \
    --engine-version 8.0 \
    --master-username chatadmin \
    --master-user-password "YourSecurePassword123!" \
    --allocated-storage 20 \
    --storage-type gp3 \
    --vpc-security-group-ids $RDS_SG_ID \
    --db-subnet-group-name chat-app-db-subnet \
    --db-name chatappdb \
    --no-publicly-accessible \
    --backup-retention-period 7
```

Wait for RDS to be available (5-10 minutes):

```bash
aws rds wait db-instance-available --db-instance-identifier chat-app-mysql

# Get RDS endpoint
export RDS_ENDPOINT=$(aws rds describe-db-instances \
    --db-instance-identifier chat-app-mysql \
    --query 'DBInstances[0].Endpoint.Address' \
    --output text)

echo "RDS Endpoint: $RDS_ENDPOINT"
```

### 6.3 Create ElastiCache Redis Cluster

```bash
# Create security group for ElastiCache
export REDIS_SG_ID=$(aws ec2 create-security-group \
    --group-name chat-app-redis-sg \
    --description "Security group for chat app Redis" \
    --vpc-id $VPC_ID \
    --query 'GroupId' \
    --output text)

# Allow Redis port from VPC
aws ec2 authorize-security-group-ingress \
    --group-id $REDIS_SG_ID \
    --protocol tcp \
    --port 6379 \
    --cidr $VPC_CIDR

# Create subnet group for ElastiCache
aws elasticache create-cache-subnet-group \
    --cache-subnet-group-name chat-app-redis-subnet \
    --cache-subnet-group-description "Subnet group for chat app Redis" \
    --subnet-ids $SUBNET_IDS

# Create Redis cluster
aws elasticache create-cache-cluster \
    --cache-cluster-id chat-app-redis \
    --cache-node-type cache.t3.micro \
    --engine redis \
    --num-cache-nodes 1 \
    --cache-subnet-group-name chat-app-redis-subnet \
    --security-group-ids $REDIS_SG_ID
```

Wait for Redis and get endpoint:

```bash
# Wait for Redis to be available
aws elasticache wait cache-cluster-available --cache-cluster-id chat-app-redis

# Get Redis endpoint
export REDIS_ENDPOINT=$(aws elasticache describe-cache-clusters \
    --cache-cluster-id chat-app-redis \
    --show-cache-node-info \
    --query 'CacheClusters[0].CacheNodes[0].Endpoint.Address' \
    --output text)

echo "Redis Endpoint: $REDIS_ENDPOINT"
```

### 6.4 Create MSK (Managed Kafka) Cluster-Amazon Managed Streaming for Apache Kafka (Amazon MSK)

```bash
# Create security group for MSK
export MSK_SG_ID=$(aws ec2 create-security-group \
    --group-name chat-app-msk-sg \
    --description "Security group for chat app MSK" \
    --vpc-id $VPC_ID \
    --query 'GroupId' \
    --output text)

# Allow Kafka ports from VPC
aws ec2 authorize-security-group-ingress \
    --group-id $MSK_SG_ID \
    --protocol tcp \
    --port 9092 \
    --cidr $VPC_CIDR

# Create MSK configuration file
cat > /tmp/msk-config.json << EOF
{
    "ClusterName": "chat-app-kafka",
    "KafkaVersion": "3.5.1",
    "NumberOfBrokerNodes": 2,
    "BrokerNodeGroupInfo": {
        "InstanceType": "kafka.t3.small",
        "ClientSubnets": [$(echo $SUBNET_IDS | awk '{print "\""$1"\", \""$2"\""}')],
        "SecurityGroups": ["$MSK_SG_ID"],
        "StorageInfo": {
            "EbsStorageInfo": {
                "VolumeSize": 20
            }
        }
    }
}
EOF

# Create MSK cluster
aws kafka create-cluster --cli-input-json file:///tmp/msk-config.json
```

Wait for MSK (15-20 minutes):

```bash
# Get cluster ARN
export MSK_CLUSTER_ARN=$(aws kafka list-clusters \
    --cluster-name-filter chat-app-kafka \
    --query 'ClusterInfoList[0].ClusterArn' \
    --output text)

# Wait and get bootstrap servers
aws kafka get-bootstrap-brokers \
    --cluster-arn $MSK_CLUSTER_ARN \
    --query 'BootstrapBrokerString' \
    --output text

export KAFKA_BOOTSTRAP=$(aws kafka get-bootstrap-brokers \
    --cluster-arn $MSK_CLUSTER_ARN \
    --query 'BootstrapBrokerString' \
    --output text)

echo "Kafka Bootstrap: $KAFKA_BOOTSTRAP"
```

---

## 7. Update Kubernetes Configurations

### 7.1 Create AWS-specific Configurations

Create a new file `k8s/aws/configmap-aws.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: chat-app-config
  namespace: chat-app
data:
  SPRING_PROFILES_ACTIVE: "k8s"
  SPRING_DATASOURCE_URL: "jdbc:mysql://YOUR_RDS_ENDPOINT:3306/chatappdb"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "YOUR_KAFKA_BOOTSTRAP:9092"
  SPRING_DATA_REDIS_HOST: "YOUR_REDIS_ENDPOINT"
  SPRING_DATA_REDIS_PORT: "6379"
```

Create a new file `k8s/aws/secret-aws.yaml`:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: chat-app-secrets
  namespace: chat-app
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: "chatadmin"
  SPRING_DATASOURCE_PASSWORD: "YourSecurePassword123!"
  JWT_SECRET: "your-production-jwt-secret-minimum-256-bits-long"
```

### 7.2 Update Deployment for ECR Image

Create a new file `k8s/aws/app-deployment-aws.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chat-app
  namespace: chat-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: chat-app
  template:
    metadata:
      labels:
        app: chat-app
    spec:
      containers:
        - name: chat-app
          image: YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/chat-app:latest
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: chat-app-config
            - secretRef:
                name: chat-app-secrets
          resources:
            requests:
              memory: "512Mi"
              cpu: "256m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
```

### 7.3 Quick Setup Script

Create a script `k8s/aws/setup-aws-configs.sh`:

```bash
#!/bin/bash

# Replace with your actual values
RDS_ENDPOINT="your-rds-endpoint.rds.amazonaws.com"
REDIS_ENDPOINT="your-redis-endpoint.cache.amazonaws.com"
KAFKA_BOOTSTRAP="your-kafka-bootstrap:9092"
AWS_ACCOUNT_ID="123456789012"
AWS_REGION="us-east-1"
DB_PASSWORD="YourSecurePassword123!"
JWT_SECRET="your-production-jwt-secret-minimum-256-bits-long"

# Create k8s/aws directory
mkdir -p k8s/aws

# Generate ConfigMap
cat > k8s/aws/configmap-aws.yaml << EOF
apiVersion: v1
kind: ConfigMap
metadata:
  name: chat-app-config
  namespace: chat-app
data:
  SPRING_PROFILES_ACTIVE: "k8s"
  SPRING_DATASOURCE_URL: "jdbc:mysql://${RDS_ENDPOINT}:3306/chatappdb"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "${KAFKA_BOOTSTRAP}"
  SPRING_DATA_REDIS_HOST: "${REDIS_ENDPOINT}"
  SPRING_DATA_REDIS_PORT: "6379"
EOF

# Generate Secrets
cat > k8s/aws/secret-aws.yaml << EOF
apiVersion: v1
kind: Secret
metadata:
  name: chat-app-secrets
  namespace: chat-app
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: "chatadmin"
  SPRING_DATASOURCE_PASSWORD: "${DB_PASSWORD}"
  JWT_SECRET: "${JWT_SECRET}"
EOF

# Generate Deployment
cat > k8s/aws/app-deployment-aws.yaml << EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: chat-app
  namespace: chat-app
spec:
  replicas: 2
  selector:
    matchLabels:
      app: chat-app
  template:
    metadata:
      labels:
        app: chat-app
    spec:
      containers:
        - name: chat-app
          image: ${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/chat-app:latest
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: chat-app-config
            - secretRef:
                name: chat-app-secrets
          resources:
            requests:
              memory: "512Mi"
              cpu: "256m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 15
EOF

echo "AWS Kubernetes configs generated in k8s/aws/"
```

---

## 8. Deploy to EKS

### Step-by-Step Deployment

```bash
# 1. Create namespace
kubectl create namespace chat-app

# 2. Apply AWS-specific configs
kubectl apply -f k8s/aws/configmap-aws.yaml
kubectl apply -f k8s/aws/secret-aws.yaml

# 3. Deploy the application
kubectl apply -f k8s/aws/app-deployment-aws.yaml

# 4. Apply service
kubectl apply -f k8s/app-service.yaml

# 5. Apply HPA (Horizontal Pod Autoscaler)
kubectl apply -f k8s/app-hpa.yaml

# 6. Verify deployment
kubectl get pods -n chat-app
kubectl get svc -n chat-app
```

---

## 9. Configure Ingress

### Option A: AWS Load Balancer Controller (Recommended)

```bash
# Install AWS Load Balancer Controller
eksctl create iamserviceaccount \
    --cluster=chat-app-cluster \
    --namespace=kube-system \
    --name=aws-load-balancer-controller \
    --attach-policy-arn=arn:aws:iam::aws:policy/ElasticLoadBalancingFullAccess \
    --approve

helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
    -n kube-system \
    --set clusterName=chat-app-cluster \
    --set serviceAccount.create=false \
    --set serviceAccount.name=aws-load-balancer-controller
```

Create `k8s/aws/ingress-aws.yaml`:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: chat-app-ingress
  namespace: chat-app
  annotations:
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/healthcheck-path: /actuator/health
    # WebSocket support
    alb.ingress.kubernetes.io/load-balancer-attributes: idle_timeout.timeout_seconds=3600
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: chat-app-service
                port:
                  number: 8080
```

Apply ingress:

```bash
kubectl apply -f k8s/aws/ingress-aws.yaml

# Get ALB URL
kubectl get ingress -n chat-app
```

### Option B: Simple LoadBalancer Service

If you prefer a simpler setup, modify the service to use LoadBalancer type:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: chat-app-service
  namespace: chat-app
  annotations:
    service.beta.kubernetes.io/aws-load-balancer-type: "nlb"
spec:
  type: LoadBalancer
  selector:
    app: chat-app
  ports:
    - port: 80
      targetPort: 8080
```

---

## 10. Verify Deployment

### Check All Resources

```bash
# Check pods
kubectl get pods -n chat-app

# Check services
kubectl get svc -n chat-app

# Check ingress/load balancer
kubectl get ingress -n chat-app

# Check HPA
kubectl get hpa -n chat-app

# View pod logs
kubectl logs -f deployment/chat-app -n chat-app
```

### Test Application

```bash
# Get the Load Balancer URL
export LB_URL=$(kubectl get ingress chat-app-ingress -n chat-app -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')

# Test health endpoint
curl http://$LB_URL/actuator/health

# Test the application
echo "Application URL: http://$LB_URL"
```

---

## 11. Monitoring & Logging

### Enable CloudWatch Container Insights

```bash
# Enable Container Insights
aws eks update-cluster-config \
    --name chat-app-cluster \
    --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'

# Install CloudWatch agent
curl -O https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/quickstart/cwagent-fluentd-quickstart.yaml

sed -i "s/{{cluster_name}}/chat-app-cluster/g" cwagent-fluentd-quickstart.yaml
sed -i "s/{{region_name}}/$AWS_REGION/g" cwagent-fluentd-quickstart.yaml

kubectl apply -f cwagent-fluentd-quickstart.yaml
```

### View Logs in CloudWatch

1. Go to AWS Console > CloudWatch > Log Groups
2. Look for `/aws/containerinsights/chat-app-cluster/application`

---

## 12. Cost Optimization Tips

### Development/Testing Environment

Use smaller instances:
- EKS nodes: `t3.small` instead of `t3.medium`
- RDS: `db.t3.micro` instead of `db.t3.small`
- ElastiCache: `cache.t3.micro`
- MSK: Consider using self-managed Kafka on EC2 or skip for dev

### Production Recommendations

1. **Reserved Instances**: Save up to 72% with 1-3 year commitments
2. **Spot Instances**: Use for non-critical workloads (up to 90% savings)
3. **Right-sizing**: Monitor and adjust instance sizes based on usage
4. **Auto-scaling**: Ensure HPA and cluster autoscaler are configured

### Estimated Monthly Costs

| Service | Dev/Test | Production |
|---------|----------|------------|
| EKS Cluster | $73 | $73 |
| EC2 Nodes (2x t3.medium) | $60 | $120 (4 nodes) |
| RDS MySQL | $25 | $100 |
| ElastiCache Redis | $12 | $50 |
| MSK Kafka | $70 | $200 |
| Load Balancer | $20 | $20 |
| Data Transfer | $10 | $50 |
| **Total** | **~$270/mo** | **~$613/mo** |

---

## 13. Cleanup

To avoid ongoing charges, delete all resources when done:

```bash
# Delete Kubernetes resources
kubectl delete namespace chat-app

# Delete EKS cluster
eksctl delete cluster --name chat-app-cluster --region $AWS_REGION

# Delete RDS
aws rds delete-db-instance \
    --db-instance-identifier chat-app-mysql \
    --skip-final-snapshot

# Delete ElastiCache
aws elasticache delete-cache-cluster --cache-cluster-id chat-app-redis

# Delete MSK
aws kafka delete-cluster --cluster-arn $MSK_CLUSTER_ARN

# Delete ECR repository
aws ecr delete-repository --repository-name chat-app --force

# Delete security groups (after services are deleted)
aws ec2 delete-security-group --group-id $RDS_SG_ID
aws ec2 delete-security-group --group-id $REDIS_SG_ID
aws ec2 delete-security-group --group-id $MSK_SG_ID

# Delete subnet groups
aws rds delete-db-subnet-group --db-subnet-group-name chat-app-db-subnet
aws elasticache delete-cache-subnet-group --cache-subnet-group-name chat-app-redis-subnet
```

---

## Quick Reference Commands

```bash
# View all resources
kubectl get all -n chat-app

# Scale deployment
kubectl scale deployment chat-app --replicas=3 -n chat-app

# Rolling update
kubectl set image deployment/chat-app chat-app=NEW_IMAGE:TAG -n chat-app

# Rollback
kubectl rollout undo deployment/chat-app -n chat-app

# View logs
kubectl logs -f deployment/chat-app -n chat-app

# Execute into pod
kubectl exec -it POD_NAME -n chat-app -- /bin/sh

# Port forward for local testing
kubectl port-forward svc/chat-app-service 8080:8080 -n chat-app
```

---

## Troubleshooting

### Pod not starting
```bash
kubectl describe pod POD_NAME -n chat-app
kubectl logs POD_NAME -n chat-app
```

### Cannot connect to RDS/Redis/Kafka
- Verify security groups allow traffic from EKS nodes
- Check VPC CIDR ranges
- Verify endpoints in ConfigMap

### Image pull errors
```bash
# Verify ECR login
aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# Check image exists
aws ecr describe-images --repository-name chat-app
```

---

## Next Steps

1. Set up CI/CD pipeline (GitHub Actions, AWS CodePipeline)
2. Configure custom domain with Route 53
3. Enable HTTPS with ACM certificate
4. Set up backup strategies for RDS
5. Implement blue-green deployments
