# GCP foundation for fund-accounting-platform. Review the cost note in deploy/README.md before `terraform apply`.
terraform {
  required_version = ">= 1.9"
  required_providers {
    google = { source = "hashicorp/google", version = "~> 7.0" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

locals {
  apis = ["container.googleapis.com", "sqladmin.googleapis.com", "artifactregistry.googleapis.com", "secretmanager.googleapis.com",
  "servicenetworking.googleapis.com", "iamcredentials.googleapis.com", "compute.googleapis.com"]
  databases = ["refdata", "trades", "accounting", "keycloak"]
}

resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.value
  disable_on_destroy = false
}

# ---------------------------------------------------------------- network (private IP for Cloud SQL)

resource "google_compute_network" "vpc" {
  name                    = "fund-accounting"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.apis]
}

resource "google_compute_subnetwork" "gke" {
  name                     = "gke"
  network                  = google_compute_network.vpc.id
  ip_cidr_range            = "10.10.0.0/20"
  private_ip_google_access = true
}

resource "google_compute_global_address" "sql_range" {
  name          = "cloudsql-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 20
  network       = google_compute_network.vpc.id
}

resource "google_service_networking_connection" "sql" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.sql_range.name]
}

resource "google_compute_global_address" "ingress" {
  name = "fund-accounting-ip"
}

# ---------------------------------------------------------------- Cloud SQL (PostgreSQL), one database + owner per service

resource "google_sql_database_instance" "pg" {
  name                = "fund-accounting-pg"
  database_version    = "POSTGRES_17"
  deletion_protection = true
  depends_on          = [google_service_networking_connection.sql]
  settings {
    tier              = var.db_tier
    edition           = "ENTERPRISE"
    availability_type = var.db_high_availability ? "REGIONAL" : "ZONAL"
    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.vpc.id
    }
    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
    }
    insights_config { query_insights_enabled = true }
  }
}

resource "google_sql_database" "db" {
  for_each = toset(local.databases)
  name     = each.value
  instance = google_sql_database_instance.pg.name
}

resource "random_password" "db" {
  for_each = toset(local.databases)
  length   = 32
  special  = false
}

resource "google_sql_user" "db" {
  for_each = toset(local.databases)
  name     = each.value
  instance = google_sql_database_instance.pg.name
  password = random_password.db[each.value].result
}

# Passwords live in Secret Manager; deploy/README.md shows how they become the Kubernetes secret.
resource "google_secret_manager_secret" "db" {
  for_each  = toset(local.databases)
  secret_id = "fund-accounting-db-${each.value}"
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "db" {
  for_each    = toset(local.databases)
  secret      = google_secret_manager_secret.db[each.value].id
  secret_data = random_password.db[each.value].result
}

# ---------------------------------------------------------------- GKE Autopilot

resource "google_container_cluster" "gke" {
  name                = "fund-accounting"
  location            = var.region
  enable_autopilot    = true
  network             = google_compute_network.vpc.id
  subnetwork          = google_compute_subnetwork.gke.id
  deletion_protection = true
  ip_allocation_policy {}
  release_channel { channel = "REGULAR" }
  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------- images + keyless CI (GitHub OIDC → Workload Identity)

resource "google_artifact_registry_repository" "images" {
  repository_id = "fund-accounting"
  format        = "DOCKER"
  location      = var.region
  depends_on    = [google_project_service.apis]
  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"
    most_recent_versions { keep_count = 20 }
  }
}

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github"
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  # Only this repository's main branch can push images.
  attribute_condition = "assertion.repository == '${var.github_repository}' && assertion.ref == 'refs/heads/main'"
  oidc { issuer_uri = "https://token.actions.githubusercontent.com" }
}

resource "google_service_account" "ci" {
  account_id   = "fund-accounting-ci"
  display_name = "GitHub Actions image publisher"
}

resource "google_service_account_iam_member" "ci_wif" {
  service_account_id = google_service_account.ci.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repository}"
}

resource "google_artifact_registry_repository_iam_member" "ci_push" {
  repository = google_artifact_registry_repository.images.name
  location   = var.region
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.ci.email}"
}
