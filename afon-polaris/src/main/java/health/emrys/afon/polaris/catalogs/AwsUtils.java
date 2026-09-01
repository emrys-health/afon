package health.emrys.afon.polaris.catalogs;

import org.apache.polaris.core.admin.model.AwsStorageConfigInfo;
import org.apache.polaris.core.admin.model.StorageConfigInfo;

import java.util.List;

public class AwsUtils {

    public static StorageConfigInfo createS3StorageConfigInfo(String roleArn,
                                                                 String bucketUrl,
                                                                 String region) {
        return AwsStorageConfigInfo
            .builder()
            .setRoleArn(roleArn)
            .setAllowedLocations(List.of(bucketUrl))
            .setRegion(region)
            .setStorageType(StorageConfigInfo.StorageTypeEnum.S3)
            .build();
    }

    public static String s3BucketUrl(String bucketName) {
        return "s3://" + bucketName;
    }

}
