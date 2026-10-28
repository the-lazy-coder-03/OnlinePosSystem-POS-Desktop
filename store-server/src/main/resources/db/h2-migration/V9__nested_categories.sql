ALTER TABLE public.menu_category ADD COLUMN parent_id INT REFERENCES public.menu_category(id);
ALTER TABLE public.menu_category ADD CONSTRAINT category_not_own_parent CHECK(parent_id IS NULL OR parent_id<>id);
ALTER TABLE public.pizza_category ADD COLUMN parent_id INT REFERENCES public.pizza_category(pizza_category_id);
ALTER TABLE public.pizza_category ADD CONSTRAINT pizza_category_not_own_parent CHECK(parent_id IS NULL OR parent_id<>pizza_category_id);
