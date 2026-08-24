aws iam update-assume-role-policy \
  --role-name S3ReadWriteRoleSF2 \
  --policy-document '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Principal": {
          "AWS": "arn:aws:iam::365074502258:user/97i91000-s"
        },
        "Action": "sts:AssumeRole"
      }
    ]
  }'