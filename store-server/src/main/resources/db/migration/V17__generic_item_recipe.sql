-- Generic recipes use the existing catalogue-record write port and identities.
-- Server validation checks menu_item and ingredient references before invoking that port.
ALTER TABLE pos.catalogue_record ADD CONSTRAINT generic_recipe_shape CHECK (
 source_type<>'menu_item_ingredient' OR (
 jsonb_typeof(data->'menu_item_id')='number' AND jsonb_typeof(data->'ingredient_id')='number'
 AND jsonb_typeof(data->'default_qty')='number' AND (data->>'default_qty')::integer>0
 AND jsonb_typeof(data->'is_removable')='boolean' AND jsonb_typeof(data->'sort_order')='number'
 ));

CREATE FUNCTION pos.guard_generic_recipe_references() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
BEGIN
 IF NEW.source_type='menu_item_ingredient' AND (
 NOT (NEW.data ?& ARRAY['menu_item_id','ingredient_id','default_qty','is_removable','sort_order']) OR
 NOT EXISTS(SELECT 1 FROM pos.catalogue_record WHERE branch_id=NEW.branch_id AND source_type='menu_item' AND data->'id'=NEW.data->'menu_item_id') OR
 NOT EXISTS(SELECT 1 FROM pos.catalogue_record WHERE branch_id=NEW.branch_id AND source_type='ingredient' AND data->'ingredient_id'=NEW.data->'ingredient_id')) THEN
 RAISE EXCEPTION 'Invalid generic recipe references' USING ERRCODE='23503'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER generic_recipe_references BEFORE INSERT OR UPDATE ON pos.catalogue_record FOR EACH ROW EXECUTE FUNCTION pos.guard_generic_recipe_references();
REVOKE ALL ON FUNCTION pos.guard_generic_recipe_references() FROM PUBLIC;
