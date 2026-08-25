echo "- AWS account:"
aws sts get-caller-identity --query "Arn"
