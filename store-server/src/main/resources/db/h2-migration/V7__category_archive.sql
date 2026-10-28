ALTER TABLE public.menu_category ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE public.pizza_category ADD COLUMN archived BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE public.menu_category ADD CONSTRAINT menu_category_archive_active CHECK (NOT archived OR NOT active);
ALTER TABLE public.pizza_category ADD CONSTRAINT pizza_category_archive_active CHECK (NOT archived OR NOT active);
