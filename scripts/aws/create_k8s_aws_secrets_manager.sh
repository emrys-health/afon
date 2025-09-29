export REGION=eu-west-2
export CLUSTERNAME=spark-cluster

# Create and configure the Kubernetes cluster in AWS. This next line can take 10 mins.
eksctl create cluster --name $CLUSTERNAME --nodes 3
eksctl utils associate-iam-oidc-provider \
  --cluster $CLUSTERNAME \
  --approve
eksctl update addon \
  --name vpc-cni \
  --cluster $CLUSTERNAME \
  --region eu-west-2

export CLUSTERID=$(aws eks describe-cluster --name spark-cluster --region eu-west-2 --query "cluster.identity.oidc.issuer" --output text | awk -F'/' '{print $NF}'  | head -c 32)

# Install secret store
# Can do this through kubectl as well. See: https://github.com/aws/secrets-store-csi-driver-provider-aws
helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install -n kube-system csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver
# Check:
kubectl --namespace=kube-system get pods -l "app=secrets-store-csi-driver"

# Create secrets. TODO these are test values:
#aws --region "$REGION" secretsmanager  create-secret --name MySecret --secret-string '{"username":"memeuser", "password":"hunter2"}'
aws --region "$REGION" secretsmanager  update-secret --secret-id $SECRET_ARN --secret-string "$(cat ~/polaris_emrys_creds.sh)"
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

kubectl apply -n kube-system -f https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml
kubectl get daemonset -n kube-system csi-secrets-store-provider-aws

# note that I had to use Option 2 from https://github.com/aws/secrets-store-csi-driver-provider-aws
# option 1 does not appear to work for me

eksctl create addon --name eks-pod-identity-agent --cluster "$CLUSTERNAME" --region "$REGION"

aws iam create-role --role-name emrys-deployment-role --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {
                "Service": "pods.eks.amazonaws.com"
            },
            "Action": [
                "sts:AssumeRole",
                "sts:TagSession"
            ]
        }
    ]
}'
ROLE_ARN=$(aws iam get-role --role-name emrys-deployment-role --query "Role.Arn" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g  | perl -pe s/\\[.*//g)

aws iam attach-role-policy --role-name emrys-deployment-role --policy-arn $POLICY_ARN

eksctl create podidentityassociation --cluster "$CLUSTERNAME" --namespace default --region "$REGION" --service-account-name nginx-pod-identity-deployment-sa --role-arn $ROLE_ARN --create-service-account true

kubectl apply -f secret_provider_class.yaml

helm repo add spark https://apache.github.io/spark-kubernetes-operator
helm repo update
helm install spark spark/spark-kubernetes-operator

sleep 30 # need to wait for spark-kubernetes-operator to start

kubectl apply -f spark_cluster.yaml

kubectl apply -f polaris-deployment.yaml

kubectl apply -f deployment_jupyter.yaml

kubectl apply -f prod-jupyter-svc.yaml