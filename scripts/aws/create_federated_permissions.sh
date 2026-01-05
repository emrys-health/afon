aws iam create-role --role-name GlueIcebergRole --assume-role-policy-document '{
   "Version": "2012-10-17",
   "Statement": [
     {
       "Effect": "Allow",
       "Principal": {
         "AWS": "arn:aws:iam::418295715997:root"
       } ,
       "Action": "sts:AssumeRole"
     }
   ]
 }'

aws iam attach-role-policy --role-name GlueIcebergRole --policy-arn  arn:aws:iam::418295715997:policy/GlueIcebergAccess

# Not sure this should be attached to my user. What about others? TODO
aws iam put-user-policy \
 --user-name philliphenry \
 --policy-name GlueIcebergAccess \
 --policy-document file://iceberg-policy.json

aws iam put-role-policy \
   --role-name GlueIcebergRole \
   --policy-name IcebergRestCatalogAccess \
   --policy-document '{
     "Version": "2012-10-17",
     "Statement": [
     {
       "Effect": "Allow",
       "Action": [
        "glue:GetCatalog"
       ],
       "Resource": [
        "arn:aws:glue:eu-west-2:418295715997:catalog"
       ]
     }
     ]
   }'