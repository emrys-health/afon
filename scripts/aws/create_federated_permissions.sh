source $CWD/scripts/config/afon-source.sh aws

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
 }"

aws iam attach-role-policy --role-name GlueIcebergRole --policy-arn  arn:aws:iam::$AWS_ACCOUNT_ID:policy/GlueIcebergAccess

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
   }"