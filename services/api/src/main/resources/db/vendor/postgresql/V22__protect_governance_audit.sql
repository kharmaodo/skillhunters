CREATE FUNCTION prevent_governance_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'governance_audit is append-only';
END;
$$;
CREATE TRIGGER governance_audit_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
 ON governance_audit FOR EACH STATEMENT EXECUTE FUNCTION prevent_governance_audit_mutation();
