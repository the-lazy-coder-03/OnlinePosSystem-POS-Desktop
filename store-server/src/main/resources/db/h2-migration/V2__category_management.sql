ALTER TABLE public.menu_category ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE dev_meta.category_ids (singleton INT PRIMARY KEY CHECK(singleton=1), next_id BIGINT NOT NULL);
INSERT INTO dev_meta.category_ids SELECT 1, COALESCE(MAX(id),0)+1 FROM public.menu_category;
CREATE TABLE dev_meta.commands (command_id UUID PRIMARY KEY, digest VARCHAR(64) NOT NULL, result_json CHARACTER LARGE OBJECT NOT NULL);
CREATE TABLE dev_meta.changes (change_id UUID PRIMARY KEY, command_id UUID NOT NULL UNIQUE REFERENCES dev_meta.commands(command_id), category_id INT NOT NULL, action VARCHAR(20) NOT NULL, revision BIGINT NOT NULL, recorded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP);
