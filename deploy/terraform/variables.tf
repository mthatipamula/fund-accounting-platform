variable "project_id" {
  type        = string
  description = "GCP project to deploy into"
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "github_repository" {
  type        = string
  description = "owner/name of the GitHub repository allowed to push images"
}

variable "db_tier" {
  type        = string
  default     = "db-custom-1-3840"
  description = "Cloud SQL machine type (1 vCPU, 3.75 GB by default)"
}

variable "db_high_availability" {
  type    = bool
  default = false
}
