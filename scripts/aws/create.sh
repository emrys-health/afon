aws s3api create-bucket --bucket emrys-afon-bucket-2 --region eu-west-2 --create-bucket-configuration LocationConstraint=eu-west-2 && \
aws s3api create-bucket --bucket $AWS_S3_BUCKET_NAME --region eu-west-2 --create-bucket-configuration LocationConstraint=eu-west-2 && \
aws iam create-role --role-name S3ReadWriteRole --assume-role-policy-document '{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
              "AWS": "arn:aws:iam::123456789012:root"
      } ,
      "Action": "sts:AssumeRole"
    }
  ]
}' && \
aws iam attach-role-policy   --role-name S3ReadWriteRole  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess

# Terrafrom assumes the loadblancer policy is provided
curl -o iam_policy.json https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/main/docs/install/iam_policy.json
aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy  --policy-document file://iam_policy.json