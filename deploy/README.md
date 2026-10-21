# Deploying to GCP (GKE Autopilot + Cloud SQL)

> **Cost.** This creates billable resources: a GKE Autopilot cluster (pods are billed by requested CPU/memory;
> this stack requests roughly 3 vCPU / 7 GB, around $150–250/month), a Cloud SQL instance (db-custom-1-3840,
> around $50/month zonal), a global load balancer (around $20/month) and small Artifact Registry and Secret Manager
> charges. Tear down with `terraform destroy` after you set `deletion_protection = false` on the cluster and the database.

## 1. Infrastructure

```bash
cd deploy/terraform
cp terraform.tfvars.example terraform.tfvars   # set project_id and github_repository
terraform init && terraform plan               # review, then:
terraform apply
```

Set the GitHub repository variables from the outputs, so CI pushes images on merges to `main` with no stored keys:
`GCP_PROJECT_ID`, `GCP_REGION`, `GCP_WORKLOAD_IDENTITY_PROVIDER` (output `workload_identity_provider`),
`GCP_CI_SERVICE_ACCOUNT` (output `ci_service_account`).

## 2. Cluster configuration

```bash
$(terraform output -raw get_credentials)
kubectl create namespace fund-accounting
```

Create the secret from Secret Manager (database passwords) plus newly generated app secrets:

```bash
gen() { openssl rand -base64 24 | tr -dc A-Za-z0-9 | head -c 24; }
kubectl -n fund-accounting create secret generic platform-secrets \
  --from-literal=DB_PASSWORD_REFDATA="$(gcloud secrets versions access latest --secret fund-accounting-db-refdata)" \
  --from-literal=DB_PASSWORD_TRADES="$(gcloud secrets versions access latest --secret fund-accounting-db-trades)" \
  --from-literal=DB_PASSWORD_ACCOUNTING="$(gcloud secrets versions access latest --secret fund-accounting-db-accounting)" \
  --from-literal=DB_PASSWORD_KEYCLOAK="$(gcloud secrets versions access latest --secret fund-accounting-db-keycloak)" \
  --from-literal=KEYCLOAK_ADMIN_PASSWORD="$(gen)" --from-literal=SEEDER_CLIENT_SECRET="$(gen)" \
  --from-literal=DEMO_ADMIN_PASSWORD="$(gen)" --from-literal=DEMO_ACCOUNTANT_PASSWORD="$(gen)" --from-literal=DEMO_VIEWER_PASSWORD="$(gen)"
kubectl -n fund-accounting create configmap keycloak-realm --from-file=realm.json=../../infra/keycloak/realm.json
```

For production, replace the manual secret with External Secrets Operator or the Secret Manager CSI driver.

## 3. Deploy

In `deploy/k8s`, replace `fund-accounting.example.com` with your domain (point its DNS A record at output `ingress_ip`),
set `DB_HOST` in `config.yaml` to output `cloud_sql_private_ip`, then:

```bash
cd deploy/k8s
kustomize edit set image \
  reference-data-service=$REGISTRY/reference-data-service:$SHA trade-service=$REGISTRY/trade-service:$SHA \
  accounting-service=$REGISTRY/accounting-service:$SHA gateway=$REGISTRY/gateway:$SHA web=$REGISTRY/web:$SHA
kubectl apply -k .
```

The managed certificate becomes active a few minutes after DNS resolves.

## Production hardening not in this demo

- Kafka: use Google Cloud Managed Service for Apache Kafka (3 brokers, RF 3) instead of the in-cluster single broker.
- Observability: install an OpenTelemetry Collector in the `observability` namespace exporting to Cloud Trace / Managed Prometheus.
- gRPC between accounting and reference data is plaintext inside the cluster and restricted by NetworkPolicy;
  add mTLS (GKE service mesh) for zero-trust networking.
- Cloud SQL: `db_high_availability = true` for a regional (failover) instance.
