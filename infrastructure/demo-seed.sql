-- Explicit local demo provisioning, NEVER loaded by application migrations.
-- Reapplying does not restore revoked memberships or global roles.
BEGIN;
INSERT INTO talent_pool(id,name) VALUES
('20000000-0000-0000-0000-000000000001','Développement & IT'),
('20000000-0000-0000-0000-000000000002','Ingénierie cloud') ON CONFLICT DO NOTHING;
WITH added AS (
  INSERT INTO app_user(id,issuer,subject,display_name) VALUES
  ('10000000-0000-0000-0000-000000000001','http://localhost:8081/realms/skillhunters','10000000-0000-0000-0000-000000000001','Alice Exemple'),
  ('10000000-0000-0000-0000-000000000002','http://localhost:8081/realms/skillhunters','10000000-0000-0000-0000-000000000002','Admin Exemple'),
  ('10000000-0000-0000-0000-000000000003','http://localhost:8081/realms/skillhunters','10000000-0000-0000-0000-000000000003','Benoît Exemple')
  ON CONFLICT DO NOTHING RETURNING id
), roles AS (
  INSERT INTO global_role(user_id,role)
  SELECT id, 'ADMIN' FROM added WHERE id='10000000-0000-0000-0000-000000000002'
)
INSERT INTO membership(user_id,pool_id,role)
SELECT id, CASE WHEN id='10000000-0000-0000-0000-000000000001'::uuid THEN '20000000-0000-0000-0000-000000000001'::uuid ELSE '20000000-0000-0000-0000-000000000002'::uuid END, 'RECRUITER'
FROM added WHERE id IN ('10000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000003');
INSERT INTO import_policy(id,label,purpose,basis_code,retention_days) VALUES
('30000000-0000-0000-0000-000000000001','Recette : documents synthétiques uniquement','Recette technique sur données synthétiques','TEST_ONLY',7)
ON CONFLICT DO NOTHING;
COMMIT;
