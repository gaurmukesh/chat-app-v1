# AWS Deployment Guide for Chat App

This guide walks you through deploying the chat application to AWS using EKS (Elastic Kubernetes Service) with managed services.

---

## Current Deployment

The application is currently deployed and running on AWS with the following configuration:

| Resource          | Value                                                                          |
|-------------------|--------------------------------------------------------------------------------|
| EKS Cluster       | `chat-app-v1-cluster` (us-east-1, K8s 1.29)                                  |
| ECR Repository    | `620179522575.dkr.ecr.us-east-1.amazonaws.com/chat-app-v1`                   |
| ALB Endpoint      | `k8s-chatapp-chatappi-*.us-east-1.elb.amazonaws.com`                         |
| RDS MySQL         | `chat-app-mysql.cod8o8ck2y1e.us-east-1.rds.amazonaws.com:3306`              |
| ElastiCache Redis | `chat-app-redis.ybwh7f.0001.use1.cache.amazonaws.com:6379`                  |
| MSK Kafka         | `b-{1,2}.chatappkafka.xo3j3c.c22.kafka.us-east-1.amazonaws.com:9094` (TLS) |
| VPC               | `vpc-070572af6742779ef`                                                       |
| Pods              | 2 replicas (HPA: 2-10, CPU target 70%)                                       |

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
export ECR_REPO=chat-app-v1

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
# From project root directory (build for linux/amd64 for EKS)
docker build --platform linux/amd64 -t chat-app-v1:latest .
```

### Tag and Push to ECR

```bash
# Tag the image
docker tag chat-app-v1:latest $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest

# Push to ECR
docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:latest
```

---

## 5. Create EKS Cluster

### Create Cluster with eksctl

```bash
# Create EKS cluster (takes 15-20 minutes)
eksctl create cluster \
    --name chat-app-v1-cluster \
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
aws eks update-kubeconfig --name chat-app-v1-cluster --region $AWS_REGION

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
    --name chat-app-v1-cluster \
    --query "cluster.resourcesVpcConfig.vpcId" \
    --output text)

# Get subnet IDs
export SUBNET_IDS=$(aws eks describe-cluster \
    --name chat-app-v1-cluster \
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

The K8s manifests in the `k8s/` directory are configured for AWS deployment. Each file contains commented-out local configuration for reference alongside the active AWS configuration.

### 7.1 ConfigMap (`k8s/configmap.yaml`)

Points to AWS managed service endpoints:

```yaml
data:
  SPRING_PROFILES_ACTIVE: "k8s"
  # Local K8s configuration (commented out)
  # SPRING_DATASOURCE_URL: "jdbc:mysql://mysql-service.chat-app.svc.cluster.local:3306/chat-app-database"
  # AWS managed services configuration
  SPRING_DATASOURCE_URL: "jdbc:mysql://chat-app-mysql.cod8o8ck2y1e.us-east-1.rds.amazonaws.com:3306/chatappdatabase"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: "b-1.chatappkafka.xo3j3c.c22.kafka.us-east-1.amazonaws.com:9094,b-2.chatappkafka.xo3j3c.c22.kafka.us-east-1.amazonaws.com:9094"
  SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL: "SSL"
  SPRING_DATA_REDIS_HOST: "chat-app-redis.ybwh7f.0001.use1.cache.amazonaws.com"
  SPRING_DATA_REDIS_PORT: "6379"
```

### 7.2 Secret (`k8s/secret.yaml`)

Contains AWS RDS credentials and JWT secret:

```yaml
stringData:
  DB_USERNAME: "chatadmin"
  DB_PASSWORD: "ChangeMe123Secure"
  JWT_SECRET: "this-is-a-very-long-secret-key-for-hs256-algorithm!!"
```

### 7.3 Deployment (`k8s/app-deployment.yaml`)

Uses ECR image with `Always` pull policy:

```yaml
image: 620179522575.dkr.ecr.us-east-1.amazonaws.com/chat-app-v1:latest
imagePullPolicy: Always
```

### 7.4 Ingress (`k8s/ingress.yaml`)

Uses AWS ALB Ingress Controller instead of nginx:

```yaml
spec:
  ingressClassName: alb
  # Annotations: internet-facing, ip target-type, health check on /actuator/health
```

### 7.5 Removed Local Manifests

The following files were removed since AWS managed services replace in-cluster StatefulSets:
- `mysql-statefulset.yaml`, `mysql-service.yaml` → replaced by Amazon RDS
- `redis-statefulset.yaml`, `redis-service.yaml` → replaced by Amazon ElastiCache
- `kafka-statefulset.yaml`, `kafka-service.yaml` → replaced by Amazon MSK

---

## 8. Deploy to EKS

### Configure kubectl

```bash
aws eks update-kubeconfig --region us-east-1 --name chat-app-v1-cluster
kubectl get nodes  # Verify connectivity
```

### Security Group Configuration

EKS pods need network access to the managed services. Add inbound rules to the managed services security group allowing traffic from the EKS cluster security group:

```bash
# Get security group IDs
EKS_SG=$(aws eks describe-cluster --name chat-app-v1-cluster --query 'cluster.resourcesVpcConfig.clusterSecurityGroupId' --output text)
MANAGED_SG="sg-029f1707a28f0edc9"  # Shared SG for RDS, MSK, ElastiCache

# Allow MySQL (3306), Redis (6379), Kafka TLS (9094) from EKS
aws ec2 authorize-security-group-ingress --group-id $MANAGED_SG --protocol tcp --port 3306 --source-group $EKS_SG
aws ec2 authorize-security-group-ingress --group-id $MANAGED_SG --protocol tcp --port 6379 --source-group $EKS_SG
aws ec2 authorize-security-group-ingress --group-id $MANAGED_SG --protocol tcp --port 9094 --source-group $EKS_SG
```

### Step-by-Step Deployment

```bash
# 1. Apply namespace
kubectl apply -f k8s/namespace.yaml

# 2. Apply config and secrets
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml

# 3. Deploy app, service, HPA, and ingress
kubectl apply -f k8s/app-deployment.yaml
kubectl apply -f k8s/app-service.yaml
kubectl apply -f k8s/app-hpa.yaml
kubectl apply -f k8s/ingress.yaml

# 4. Verify deployment
kubectl get pods -n chat-app
kubectl get svc -n chat-app
kubectl rollout status deployment/chat-app -n chat-app
```

---

## 9. Configure Ingress (AWS Load Balancer Controller)

### 9.1 Associate IAM OIDC Provider

```bash
eksctl utils associate-iam-oidc-provider \
    --cluster chat-app-v1-cluster \
    --region us-east-1 \
    --approve
```

### 9.2 Create IAM Policy

```bash
# Download the latest IAM policy for the ALB controller
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.12.0/docs/install/iam_policy.json

# Create the IAM policy
aws iam create-policy \
    --policy-name AWSLoadBalancerControllerIAMPolicy \
    --policy-document file://iam_policy.json
```

**Note:** Use the v2.12.0 policy (or later) — older versions may be missing permissions like `ec2:GetSecurityGroupsForVpc` and `elasticloadbalancing:DescribeListenerAttributes`.

### 9.3 Create IAM Service Account

```bash
eksctl create iamserviceaccount \
    --cluster=chat-app-v1-cluster \
    --namespace=kube-system \
    --name=aws-load-balancer-controller \
    --role-name AmazonEKSLoadBalancerControllerRole \
    --attach-policy-arn=arn:aws:iam::620179522575:policy/AWSLoadBalancerControllerIAMPolicy \
    --approve \
    --region us-east-1
```

### 9.4 Install via Helm

```bash
helm repo add eks https://aws.github.io/eks-charts
helm repo update eks

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
    -n kube-system \
    --set clusterName=chat-app-v1-cluster \
    --set serviceAccount.create=false \
    --set serviceAccount.name=aws-load-balancer-controller \
    --set region=us-east-1 \
    --set vpcId=vpc-070572af6742779ef
```

### 9.5 Verify ALB Provisioning

The Ingress resource (`k8s/ingress.yaml`) is already configured for ALB. After the controller is running, it will provision an internet-facing ALB:

```bash
# Check controller is running
kubectl get deployment aws-load-balancer-controller -n kube-system

# Check ALB address (may take 1-2 minutes)
kubectl get ingress -n chat-app
```

The ALB URL will appear in the ADDRESS column (e.g., `k8s-chatapp-chatappi-*.us-east-1.elb.amazonaws.com`).

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
    --name chat-app-v1-cluster \
    --logging '{"clusterLogging":[{"types":["api","audit","authenticator","controllerManager","scheduler"],"enabled":true}]}'

# Install CloudWatch agent
curl -O https://raw.githubusercontent.com/aws-samples/amazon-cloudwatch-container-insights/latest/k8s-deployment-manifest-templates/deployment-mode/daemonset/container-insights-monitoring/quickstart/cwagent-fluentd-quickstart.yaml

sed -i "s/{{cluster_name}}/chat-app-v1-cluster/g" cwagent-fluentd-quickstart.yaml
sed -i "s/{{region_name}}/$AWS_REGION/g" cwagent-fluentd-quickstart.yaml

kubectl apply -f cwagent-fluentd-quickstart.yaml
```

### View Logs in CloudWatch

1. Go to AWS Console > CloudWatch > Log Groups
2. Look for `/aws/containerinsights/chat-app-v1-cluster/application`

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
eksctl delete cluster --name chat-app-v1-cluster --region $AWS_REGION

# Delete RDS
aws rds delete-db-instance \
    --db-instance-identifier chat-app-mysql \
    --skip-final-snapshot

# Delete ElastiCache
aws elasticache delete-cache-cluster --cache-cluster-id chat-app-redis

# Delete MSK
aws kafka delete-cluster --cluster-arn $MSK_CLUSTER_ARN

# Delete ECR repository
aws ecr delete-repository --repository-name chat-app-v1 --force

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
- Verify the managed services SG allows inbound from the EKS cluster SG on the correct ports (3306, 6379, 9094)
- Check that EKS and managed services are in the same VPC (`vpc-070572af6742779ef`)
- Verify endpoints in ConfigMap match the actual AWS service endpoints
- For MSK, ensure `SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL: "SSL"` is set (MSK uses TLS on port 9094)

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
