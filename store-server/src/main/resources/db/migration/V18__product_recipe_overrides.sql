-- PostgreSQL stores working recipe rows through the existing secured catalogue port.
ALTER TABLE pos.catalogue_record ADD CONSTRAINT burger_override_shape CHECK (
 source_type<>'burger_item_component_override' OR (
 data ?& ARRAY['burger_id','component_id','excluded','is_removable','sort_order']
 AND jsonb_typeof(data->'burger_id')='number'
 AND jsonb_typeof(data->'component_id')='number'
 AND jsonb_typeof(data->'excluded')='boolean'
 AND jsonb_typeof(data->'is_removable') IN ('boolean','null')
 AND jsonb_typeof(data->'sort_order') IN ('number','null')));

CREATE FUNCTION pos.guard_burger_override_references() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,pos AS $$
BEGIN
 IF NEW.source_type='burger_item_component_override' AND (
 NOT EXISTS(SELECT 1 FROM pos.catalogue_record WHERE branch_id=NEW.branch_id AND source_type='menu_item' AND data->'id'=NEW.data->'burger_id') OR
 NOT EXISTS(SELECT 1 FROM pos.catalogue_record WHERE branch_id=NEW.branch_id AND source_type='burger_component' AND data->'component_id'=NEW.data->'component_id')) THEN
 RAISE EXCEPTION 'Invalid burger override references' USING ERRCODE='23503';
 END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER burger_override_references AFTER INSERT OR UPDATE ON pos.catalogue_record
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pos.guard_burger_override_references();
REVOKE ALL ON FUNCTION pos.guard_burger_override_references() FROM PUBLIC;

-- Aggregate creation inserts source parents and their links in one bulk statement.
-- PostgreSQL may reorder that INSERT's join output; check references at transaction commit,
-- just as a deferred foreign key, after every source row is visible.
DROP TRIGGER generic_recipe_references ON pos.catalogue_record;
CREATE CONSTRAINT TRIGGER generic_recipe_references AFTER INSERT OR UPDATE ON pos.catalogue_record
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION pos.guard_generic_recipe_references();
