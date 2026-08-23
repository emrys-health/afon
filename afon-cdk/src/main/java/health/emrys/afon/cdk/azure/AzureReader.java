package health.emrys.afon.cdk.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class AzureReader {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(AzureReader.class);

    private final String containerName;
    private final String connectionString;

    // Convert simple wildcard (*, ?) to regex
    private static Pattern wildcardToRegex(String wildcard) {
        String regex = wildcard
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return Pattern.compile(regex);
    }

    public AzureReader(AfonPropertyProvider properties) {
        containerName    = properties.getValue(AfonProperty.AZURE_BUCKET);
        connectionString = properties.getValue(AfonProperty.AZURE_CONNECTION_STRING);
    }

    public Set<BlobItem> list(String wildcard) {
        Set<BlobItem> result  = new HashSet<>();
        Pattern       pattern = wildcardToRegex(wildcard);

        BlobContainerClient containerClient = new BlobContainerClientBuilder()
            .connectionString(connectionString)
            .containerName(containerName)
            .buildClient();

        for (BlobItem blobItem : containerClient.listBlobs()) {
            if (pattern.matcher(blobItem.getName()).matches()) {
                log.info("{} : {}", blobItem.getName(), blobItem.getProperties().getLastModified());
                result.add(blobItem);
            }
        }
        return result;
    }

    public static void main(String[] args) {
        AzureReader app = new AzureReader(new FilePropertyProvider());
        app.list("my_project_folder*metadata.json");
    }


}
