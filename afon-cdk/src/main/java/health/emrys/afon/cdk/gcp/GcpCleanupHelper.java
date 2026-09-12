package health.emrys.afon.cdk.gcp;

import health.emrys.afon.cdk.CleanupHelper;
import health.emrys.afon.config.AfonProperty;
import health.emrys.afon.config.AfonPropertyProvider;
import health.emrys.afon.config.FilePropertyProvider;

public class GcpCleanupHelper extends CleanupHelper {

    private final AfonPropertyProvider properties;

    public GcpCleanupHelper(AfonPropertyProvider properties) {
        this.properties = properties;
    }

    public String cleanupCommands() {
        return String.format("""
               #!/bin/bash
               PROJECT_ID="afon-core"
               VPC_NAME="gke-private-vpc"
               REGION="%s"
               
               echo "Attempting to wipe all resources associated with $VPC_NAME..."
               
               # 1. Delete GKE Clusters (This is the most important step)
               echo "Checking for GKE clusters..."
               for cluster in $(gcloud container clusters list --project=$PROJECT_ID --format="value(name)" --filter="network=$VPC_NAME"); do
                 echo "Deleting GKE Cluster: $cluster"
                 gcloud container clusters delete $cluster --region=$REGION --project=$PROJECT_ID --quiet --async
               done
               
               # 2. Delete Managed Instance Groups (GKE Node Pools)
               echo "Deleting Instance Groups..."
               for ig in $(gcloud compute instance-groups managed list --project=$PROJECT_ID --format="value(name)" --filter="network=$VPC_NAME"); do
                 gcloud compute instance-groups managed delete $ig --region=$REGION --project=$PROJECT_ID --quiet
               done
               
               # 3. Delete Load Balancer Forwarding Rules
               echo "Deleting Forwarding Rules..."
               for fr in $(gcloud compute forwarding-rules list --project=$PROJECT_ID --format="value(name)" --filter="network=$VPC_NAME"); do
                 gcloud compute forwarding-rules delete $fr --region=$REGION --project=$PROJECT_ID --quiet
               done
               
               # 4. Delete Firewall Rules
               echo "Deleting Firewall Rules..."
               for rule in $(gcloud compute firewall-rules list --project=$PROJECT_ID --format="value(name)" --filter="network=$VPC_NAME"); do
                 gcloud compute firewall-rules delete $rule --project=$PROJECT_ID --quiet
               done
               
               # 5a. Delete GKE Private Endpoint Forwarding Rules
               echo "Deleting GKE Private Endpoint Forwarding Rules..."
               for fr in $(gcloud compute forwarding-rules list --project=$PROJECT_ID --regions=$REGION --format="value(name)" --filter="name~gk3-"); do
                 gcloud compute forwarding-rules delete $fr --region=$REGION --project=$PROJECT_ID --quiet
               done
               
               # 5b. Delete GKE Private Service Connect Addresses
               echo "Deleting GKE Internal Addresses..."
               for addr in $(gcloud compute addresses list --project=$PROJECT_ID --regions=$REGION --format="value(name)" --filter="name~gk3-"); do
                 gcloud compute addresses delete $addr --region=$REGION --project=$PROJECT_ID --quiet
               done
               
               # 5c. Check for Service Attachments (if any)
               echo "Deleting Service Attachments..."
               for sa in $(gcloud compute service-attachments list --project=$PROJECT_ID --regions=$REGION --format="value(name)"); do
                 gcloud compute service-attachments delete $sa --region=$REGION --project=$PROJECT_ID --quiet
               done
               
               # 5d. Delete Subnets
               echo "Deleting Subnets..."
               for subnet in $(gcloud compute networks subnets list --project=$PROJECT_ID --network=$VPC_NAME --format="value(name)"); do
                 gcloud compute networks subnets delete $subnet --region=$REGION --project=$PROJECT_ID --quiet
               done
               
               gcloud compute routers delete gke-router \\
                   --region=$REGION \\
                   --project=$PROJECT_ID \\
                   --quiet
               
               # 6. Finally, Delete the VPC
               echo "Deleting the VPC Network..."
               gcloud compute networks delete $VPC_NAME --project=$PROJECT_ID --quiet
               
               echo "Cleanup complete."
               """, properties.getValue(AfonProperty.GCP_REGION));
    }

    public static void main(String[] args) {
        var properties = new FilePropertyProvider();
        outputCleanupCommands(new GcpCleanupHelper(properties).cleanupCommands(), args);
    }

}
