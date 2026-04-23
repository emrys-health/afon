OUTPUT_FILE=versions.txt

write_delimiter()
{
  echo =============== >> $OUTPUT_FILE
  echo >> $OUTPUT_FILE
}

echo Tool versions for: > $OUTPUT_FILE
echo user: $(whoami) >> $OUTPUT_FILE
echo OS:   $(uname -a) >> $OUTPUT_FILE
write_delimiter

echo "kubectl:" >> $OUTPUT_FILE
kubectl version 2>/dev/null >> $OUTPUT_FILE
write_delimiter

echo "tofu:" >> $OUTPUT_FILE
tofu --version >> $OUTPUT_FILE
write_delimiter

echo "mvn:" >> $OUTPUT_FILE
mvn --version >> $OUTPUT_FILE
write_delimiter

echo "java:" >> $OUTPUT_FILE
java --version >> $OUTPUT_FILE
write_delimiter

echo "aws:" >> $OUTPUT_FILE
aws --version >> $OUTPUT_FILE
write_delimiter

echo "az:" >> $OUTPUT_FILE
az --version >> $OUTPUT_FILE
write_delimiter

echo "gcloud:" >> $OUTPUT_FILE
gcloud --version >> $OUTPUT_FILE
write_delimiter



