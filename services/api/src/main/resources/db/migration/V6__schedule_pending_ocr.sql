-- Only pre-OCR results are reprocessed. Human revisions do not exist yet.
UPDATE document_analysis SET state='PENDING',attempts=0,lease_token=NULL,lease_until=NULL,
    available_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,code='OCR_UPGRADE',
    result_json=NULL,parser_version=NULL,av_version=NULL
WHERE state='OCR_REQUIRED' AND import_id IN
    (SELECT id FROM document_import WHERE state='QUARANTINED' AND expires_at>CURRENT_TIMESTAMP);
