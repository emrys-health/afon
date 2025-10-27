#!/bin/bash

export REGION=eu-west-2
export CLUSTERNAME=spark-cluster

echo "Create and configure the Kubernetes cluster ($CLUSTERNAME) in AWS. This can take 10-15 mins."
eksctl create cluster --name $CLUSTERNAME --nodes 3
echo "Associating OIDC provider"
eksctl utils associate-iam-oidc-provider \
  --cluster $CLUSTERNAME \
  --approve
echo "Updaing VPC CNI addon"
eksctl update addon \
  --name vpc-cni \
  --cluster $CLUSTERNAME \
  --region eu-west-2

export CLUSTERID=$(aws eks describe-cluster --name spark-cluster --region eu-west-2 --query "cluster.identity.oidc.issuer" --output text | awk -F'/' '{print $NF}'  | head -c 32)

echo "Install secret store. It might be there already and that's fine"
# Can do this through kubectl as well. See: https://github.com/aws/secrets-store-csi-driver-provider-aws
helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
helm install -n kube-system csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver
echo "Let's see if the drivers are there"
kubectl --namespace=kube-system get pods -l "app=secrets-store-csi-driver"
echo "Success. Cluster installed"
echo "---------------"

echo "Create secrets. This references scripts on your local drive. Make sure they exist"
echo "You might need to run gcloud auth application-default login ... again (see the create.sh script in the gcp directory)"
echo "This will refresh the token before deploying it as a token (see Afon bug ticket #10)"
aws --region "$REGION" secretsmanager  create-secret --name CloudDetails --secret-string "$(cat ~/emrys.sh)"
aws --region "$REGION" secretsmanager  create-secret --name application_default_credentials.json --secret-string "$(cat ~/.config/gcloud/application_default_credentials.json)"

echo "Waiting 10s for the secrets to be created..."
sleep 10

GOOGLE_SECRET_ARN=$(aws secretsmanager list-secrets --query "SecretList[?Name=='application_default_credentials.json'].ARN" --output text --no-cli-pager | tr -d '[:cntrl:]' | tr -d '\n')
SECRET_ARN=$(aws secretsmanager list-secrets --query "SecretList[?Name=='CloudDetails'].ARN" --output text --no-cli-pager | tr -d '[:cntrl:]' | tr -d '\n')

if [[ ! -v GOOGLE_SECRET_ARN ]]; then
        echo "GOOGLE_SECRET_ARN not set. Are you sure GCP is on your machine and you've run gcloud auth application-default login ... ?"
        exit 1
fi

if [[ ! -v SECRET_ARN ]]; then
        echo "SECRET_ARN not set. Are you sure you have an ~/emrys.sh exporting secrets?"
        exit 1
fi

echo "Success. AWS Secrets set."
echo "---------------"

echo "Creating the security policies..."
aws iam create-policy --policy-name nginx-deployment-policy --policy-document '{
    "Version": "2012-10-17",
    "Statement": [ {
        "Effect": "Allow",
        "Action": ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"],
        "Resource": ["'$SECRET_ARN'"]
    },
    {
            "Effect": "Allow",
            "Action": ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"],
            "Resource": ["'$GOOGLE_SECRET_ARN'"]
        }
     ]
}'
POLICY_ARN=$(aws iam list-policies --query "Policies[?PolicyName=='nginx-deployment-policy'].Arn" --output text | tr -d '[:cntrl:]' | perl -pe s/.*arn/arn/g | perl -pe s/\\[.*//g)
if [[ ! -v POLICY_ARN ]]; then
        echo "POLICY_ARN not set. Apparently we could not create nginx-deployment-policy"
        exit 1
fi

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
if [[ ! -v ROLE_ARN ]]; then
        echo "ROLE_ARN not set. Apparently, we could not create emrys-deployment-role"
        exit 1
fi

aws iam attach-role-policy --role-name emrys-deployment-role --policy-arn $POLICY_ARN

echo "Success. AWS permissions set up"
echo "---------------"


echo "Configuring K8s so it can talk to AWS IAM"
eksctl utils associate-iam-oidc-provider --region="$REGION" --cluster="$CLUSTERNAME" --approve
echo "Installing the AWS/K8s bridge for secrets"
kubectl apply -n kube-system -f https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml
echo "Waiting 10s for it to be set up"
sleep 20
echo "Check it's set up:"
kubectl get daemonset -n kube-system csi-secrets-store-provider-aws || echo "secret store driver not installed" && exit 1
# note that I had to use Option 2 from https://github.com/aws/secrets-store-csi-driver-provider-aws
# option 1 does not appear to work for me
echo "Installing the identity agent so pods can assume AWS roles"
eksctl create addon --name eks-pod-identity-agent --cluster "$CLUSTERNAME" --region "$REGION"
echo "Creating a service account that is associated with our AWS role"
sleep 10 # below can sometimes fail - apparently, the above line is asynchronous. So, let's guess a time to wait
eksctl create podidentityassociation --cluster "$CLUSTERNAME" --namespace default --region "$REGION" --service-account-name nginx-pod-identity-deployment-sa --role-arn $ROLE_ARN --create-service-account true
echo "Configuring the secrets to which our pods will refer"
kubectl apply -f secret_provider_class.yaml
kubectl apply -f secret_provider_class_google.yaml
echo "Success. K8s can now assume AWS roles"
echo "---------------"

echo "Installing our software into the K8s cluster"
echo "Installing Polaris into K8s"
#kubectl apply -f polaris-deployment.yaml
kubectl apply -f deployment_polaris.yaml
echo "Installing Jupyter into K8s. Note Jupyter is a big Docker image so it can take >60s for the K8s pod to start"
kubectl apply -f deployment_jupyter.yaml
echo "Installing an easier way to manage Spark clusters"
helm repo add spark https://apache.github.io/spark-kubernetes-operator
helm repo update
helm install spark spark/spark-kubernetes-operator
echo "Waiting 30s for the Spark K8s cluster to setup..."
sleep 30
echo "Start a Spark cluster in K8s"
kubectl apply -f spark_cluster.yaml
echo "Opening Jupyter up to the greater internet"
kubectl apply -f prod-jupyter-svc.yaml
echo "Opening Polaris to the greate internet"
kubectl apply -f prod-polaris-svc.yaml
echo "Giving Jupyter a fixed internal host name"
kubectl apply -f prod-jupyter-internal-svc.yaml
echo "Giving Polaris a fixed internal host name"
kubectl apply -f prod-polaris-internal-svc.yaml
echo "Opening the Spark web GUI to the outside world. You might not want this as it's not password protected. But it is useful for debugging"
kubectl apply -f prod-master-svc.yaml
echo "Use the EXTERNAL-IP for Jupyter below to see it in your browser:"
kubectl get services
echo "Note that it can take a minute or so for the EXTERNAL-IPs to be assigned"