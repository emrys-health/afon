source $AFON_ROOT/scripts/config/afon-source.sh aws

VPCS=$(aws ec2 describe-vpcs --query "Vpcs[?Tags[?Key=='Name' && Value=='cdktf-eks-vpc-$CLUSTER_NAME']].VpcId" --output text)
if [ -n "$VPCS" ]; then
  echo "VPCS running: $VPCS"
else
  echo "No VPCS running"
fi
