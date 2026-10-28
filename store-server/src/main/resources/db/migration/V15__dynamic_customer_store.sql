-- Preserve existing profile snapshots; new profiles use the assigned store metadata.
CREATE OR REPLACE FUNCTION pos.write_customer(proof text,terminal text,id uuid,expected bigint,operation text,data jsonb,command uuid)
RETURNS bigint LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
DECLARE b integer;who uuid;previous pos.local_customer;next_revision bigint;stamp timestamptz;profile_value jsonb;name_value text;target pos.local_customer;k text;
BEGIN
 SELECT branch_id INTO b FROM pos.store_identity;
 PERFORM position FROM pos.stream_head WHERE branch_id=b FOR UPDATE;
 who=pos.require_customer_session(proof,terminal,CASE operation WHEN 'ARCHIVE' THEN 'customers.archive' WHEN 'BAN' THEN 'customers.ban' WHEN 'LINK' THEN 'customers.resolve' WHEN 'RESOLVE_SAVE' THEN 'customers.resolve' ELSE 'customers.manage' END);
 SELECT * INTO previous FROM pos.local_customer WHERE branch_id=b AND customer_id=id FOR UPDATE;
 IF operation IN ('SAVE','RESOLVE_SAVE') AND expected=-1 AND NOT FOUND THEN NULL;
 ELSIF NOT FOUND OR previous.revision<>expected OR previous.canonical_customer_id IS NOT NULL THEN
  RAISE EXCEPTION 'Customer changed; reload' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM pos.customer_alias WHERE alias_id=id AND customer_id<>id) THEN RAISE EXCEPTION 'Customer identity is already linked' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM pos.local_customer WHERE customer_id=id AND branch_id<>b) THEN RAISE EXCEPTION 'Foreign customer' USING ERRCODE='42501'; END IF;
 stamp=clock_timestamp();next_revision=coalesce(previous.revision+1,0);
 IF operation IN ('SAVE','RESOLVE_SAVE') THEN
  profile_value=data;
  IF jsonb_typeof(data)<>'object' OR EXISTS(SELECT 1 FROM jsonb_each(data) v WHERE jsonb_typeof(value)<>'string' OR key NOT IN
   ('first_name','last_name','phone1','phone2','email','house_number','street','area','complex_name','preferred_store','city','postal_code','google_place_id','formatted_address','latitude','longitude','province','country')) THEN
   RAISE EXCEPTION 'Invalid profile fields' USING ERRCODE='23514'; END IF;
  name_value=btrim(coalesce(data->>'first_name','')||' '||coalesce(data->>'last_name',''));
  IF name_value='' OR coalesce(data->>'phone1','') !~ '^\+[1-9][0-9]{7,14}$' OR length(data->>'phone1')>20
   OR length(coalesce(data->>'email',''))>255 THEN RAISE EXCEPTION 'Invalid name or phone' USING ERRCODE='23514'; END IF;
  IF data->>'preferred_store' IS DISTINCT FROM coalesce(previous.profile->>'preferred_store',(SELECT COALESCE(s.store_name,(SELECT c.data->>'name' FROM pos.catalogue_record c WHERE c.branch_id=s.branch_id AND c.source_type='branch' LIMIT 1),'Store '||s.branch_id) FROM pos.store_identity s WHERE s.branch_id=b)) THEN
   RAISE EXCEPTION 'Only the customer changes preferred store' USING ERRCODE='42501'; END IF;
  IF EXISTS(SELECT 1 FROM pos.local_customer WHERE branch_id=b AND customer_id<>id AND canonical_customer_id IS NULL AND profile->>'phone1'=data->>'phone1') THEN RAISE EXCEPTION 'Contact collision' USING ERRCODE='23505'; END IF;
  INSERT INTO pos.local_customer(customer_id,branch_id,display_name,phone,address,profile,normalized_phone,normalized_email,preferred_branch,global_id,revision,updated_at)
  VALUES(id,b,name_value,data->>'phone1',data->>'formatted_address',data,data->>'phone1',nullif(lower(btrim(data->>'email')),''),b,id,0,stamp)
  ON CONFLICT(customer_id) DO UPDATE SET display_name=excluded.display_name,phone=excluded.phone,address=excluded.address,
   profile=excluded.profile,normalized_phone=excluded.normalized_phone,normalized_email=excluded.normalized_email,revision=next_revision,updated_at=stamp,sync_state='PENDING';
  IF previous.profile IS NULL THEN UPDATE pos.local_customer SET field_times=jsonb_set(field_times,'{preferred_store}',to_jsonb(stamp)) WHERE customer_id=id; END IF;
  -- Address is one atomic timestamped group; other contact fields are individually versioned.
  FOR k IN SELECT key FROM jsonb_each(data) WHERE key NOT IN ('preferred_store','house_number','street','area','complex_name','city','postal_code','google_place_id','formatted_address','latitude','longitude','province','country') LOOP
   IF previous.profile->k IS DISTINCT FROM data->k THEN UPDATE pos.local_customer SET field_times=jsonb_set(field_times,ARRAY[k],to_jsonb(stamp)) WHERE customer_id=id; END IF;
  END LOOP;
  IF previous.profile IS NULL OR (previous.profile-ARRAY['first_name','last_name','phone1','phone2','email','preferred_store']) IS DISTINCT FROM (data-ARRAY['first_name','last_name','phone1','phone2','email','preferred_store']) THEN
   UPDATE pos.local_customer SET field_times=jsonb_set(field_times,'{address}',to_jsonb(stamp)) WHERE customer_id=id;
  END IF;
 ELSIF operation='ARCHIVE' THEN
  UPDATE pos.local_customer SET archived=(data->>'value')::boolean,revision=next_revision,updated_at=stamp WHERE customer_id=id;
 ELSIF operation='BAN' THEN
  IF coalesce((data->>'value')::boolean,true) AND length(btrim(coalesce(data->>'reason','')))=0 THEN RAISE EXCEPTION 'Ban reason required' USING ERRCODE='23514'; END IF;
  IF length(coalesce(data->>'reason',''))>2000 THEN RAISE EXCEPTION 'Reason too long' USING ERRCODE='23514'; END IF;
  UPDATE pos.local_customer SET banned=(data->>'value')::boolean,ban_reason=CASE WHEN (data->>'value')::boolean THEN data->>'reason' ELSE '' END,
    ban_changed_at=stamp,field_times=jsonb_set(field_times,'{ban}',to_jsonb(stamp)),revision=next_revision,updated_at=stamp,sync_state='PENDING' WHERE customer_id=id;
 ELSIF operation='LINK' THEN
  SELECT * INTO target FROM pos.local_customer WHERE branch_id=b AND customer_id=(data->>'target')::uuid AND canonical_customer_id IS NULL FOR UPDATE;
  IF NOT FOUND OR target.customer_id=id THEN RAISE EXCEPTION 'Invalid link target' USING ERRCODE='23514'; END IF;
  UPDATE pos.local_customer SET canonical_customer_id=target.customer_id,normalized_phone=NULL,normalized_email=NULL,revision=next_revision,updated_at=stamp WHERE customer_id=id;
  INSERT INTO pos.customer_alias VALUES(id,b,target.customer_id) ON CONFLICT(alias_id) DO UPDATE SET customer_id=excluded.customer_id;
  UPDATE pos.customer_alias SET customer_id=target.customer_id WHERE branch_id=b AND customer_id=id;
  UPDATE pos.local_customer SET canonical_customer_id=target.customer_id WHERE branch_id=b AND canonical_customer_id=id;
  IF previous.banned AND NOT target.banned THEN UPDATE pos.local_customer SET banned=true,ban_reason=previous.ban_reason,ban_changed_at=stamp,field_times=jsonb_set(field_times,'{ban}',to_jsonb(stamp)),revision=revision+1,sync_state='PENDING' WHERE customer_id=target.customer_id; END IF;
 ELSE RAISE EXCEPTION 'Unsupported customer operation' USING ERRCODE='23514';
 END IF;
 INSERT INTO pos.customer_status_history VALUES(command,b,id,operation,who,terminal,CASE WHEN operation='BAN' THEN coalesce(data->>'reason','') ELSE '' END,stamp,command);
 RETURN next_revision;
END $$;
