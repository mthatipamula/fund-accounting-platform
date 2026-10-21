output "cloud_sql_private_ip" { value = google_sql_database_instance.pg.private_ip_address }
output "registry" { value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}" }
output "ingress_ip" { value = google_compute_global_address.ingress.address }
output "workload_identity_provider" { value = google_iam_workload_identity_pool_provider.github.name }
output "ci_service_account" { value = google_service_account.ci.email }
output "get_credentials" { value = "gcloud container clusters get-credentials ${google_container_cluster.gke.name} --region ${var.region} --project ${var.project_id}" }
