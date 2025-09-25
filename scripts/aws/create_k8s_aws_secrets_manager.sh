export REGION=eu-west-2
export CLUSTERNAME=spark-cluster

# Create and configure the Kubernetes cluster in AWS
eksctl create cluster --name spark-cluster --nodes 3
eksctl utils associate-iam-oidc-provider \
  --cluster spark-cluster \
  --approve
eksctl update addon \
  --name vpc-cni \
  --cluster spark-cluster \
  --region eu-west-2

export CLUSTERID=$(aws eks describe-cluster --name spark-cluster --region eu-west-2 --query "cluster.identity.oidc.issuer" --output text | awk -F'/' '{print $NF}'  | head -c 32)

# Install secret store
# Can do this through kubectl as well. See: https://github.com/aws/secrets-store-csi-driver-provider-aws
helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install -n kube-system csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver
# Check:
kubectl --namespace=kube-system get pods -l "app=secrets-store-csi-driver"

# Create secrets. TODO these are test values:
aws --region "$REGION" secretsmanager  create-secret --name MySecret --secret-string '{"username":"memeuser", "password":"hunter2"}'
SECRET_ARN=$(aws secretsmanager list-secrets --query "SecretList[?Name=='MySecret'].ARN" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g | perl -pe s/\\[.*//g)

aws iam create-policy --policy-name nginx-deployment-policy --policy-document '{
    "Version": "2012-10-17",
    "Statement": [ {
        "Effect": "Allow",
        "Action": ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"],
        "Resource": ["'$SECRET_ARN'"]
    } ]
}'
#
POLICY_ARN=$(aws iam list-policies --query "Policies[?PolicyName=='nginx-deployment-policy'].Arn" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g | perl -pe s/\\[.*//g)

eksctl utils associate-iam-oidc-provider --region="$REGION" --cluster="$CLUSTERNAME" --approve # Only run this once


eksctl create iamserviceaccount --name nginx-irsa-deployment-sa --region="$REGION" --cluster "$CLUSTERNAME" --attach-policy-arn "$POLICY_ARN" --approve --override-existing-serviceaccounts

# This wasn't in the docs...
kubectl apply -n kube-system -f https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml
kubectl get daemonset -n kube-system csi-secrets-store-provider-aws




# TEST

aws iam update-assume-role-policy --role-name eksctl-spark-cluster-addon-iamserviceaccount--Role1-mjLHFL0CzdVN  --policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::123456789012:oidc-provider/oidc.eks.'$REGION'.amazonaws.com/id/'$CLUSTERID'"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.eks.'$REGION'.amazonaws.com/id/'$CLUSTERID':sub": "system:serviceaccount:default:nginx-irsa-deployment-sa"
        }
      }
    }
  ]
}'
