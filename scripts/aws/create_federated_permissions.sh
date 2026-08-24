source $AFON_ROOT/scripts/config/afon-source.sh aws

if aws iam get-role --role-name GlueIcebergRole >/dev/null 2>&1; then
  echo "- Role GlueIcebergRole already exists, skipping"
else
  echo "- Creating role GlueIcebergRole"
  aws iam create-role --role-name GlueIcebergRole --assume-role-policy-document "{
   \"Version\": \"2012-10-17\",
   \"Statement\": [
     {
       \"Effect\": \"Allow\",
       \"Principal\": {
         \"AWS\": \"arn:aws:iam::$AWS_ACCOUNT_ID:root\"
       } ,
       \"Action\": \"sts:AssumeRole\"
     }
   ]
 }" || exit 1
fi

if aws iam list-attached-role-policies --role-name GlueIcebergRole --query "AttachedPolicies[?PolicyName=='GlueIcebergAccess'].PolicyName" --output text | grep -q GlueIcebergAccess; then
  echo "- Policy GlueIcebergAccess already attached to GlueIcebergRole, skipping"
else
  echo "- Attaching policy GlueIcebergAccess to GlueIcebergRole"
  aws iam attach-role-policy --role-name GlueIcebergRole --policy-arn  arn:aws:iam::$AWS_ACCOUNT_ID:policy/GlueIcebergAccess || exit 1
fi

echo "- Setting inline policy IcebergRestCatalogAccess on GlueIcebergRole"
aws iam put-role-policy \
   --role-name GlueIcebergRole \
   --policy-name IcebergRestCatalogAccess \
   --policy-document "{
     \"Version\": \"2012-10-17\",
     \"Statement\": [
     {
       \"Effect\": \"Allow\",
       \"Action\": [
        \"glue:GetCatalog\"
       ],
       \"Resource\": [
        \"arn:aws:glue:$AWS_REGION:$AWS_ACCOUNT_ID:catalog\"
       ]
     }
     ]
   }" || exit 1
