// Runtime settings. Replaced per environment (a Kubernetes ConfigMap mounts its own copy).
window.__CONFIG__ = {
  oidcAuthority: 'http://localhost:8180/realms/fund-accounting',
  oidcClientId: 'fund-accounting-web',
};
