CREATE TABLE public.menu_item_ingredient (
 menu_item_id INT NOT NULL REFERENCES public.menu_item(id),
 ingredient_id INT NOT NULL REFERENCES public.ingredient(ingredient_id),
 default_qty INT NOT NULL DEFAULT 1 CHECK(default_qty>0),
 is_removable BOOLEAN NOT NULL DEFAULT TRUE,sort_order INT NOT NULL DEFAULT 0,
 PRIMARY KEY(menu_item_id,ingredient_id)
);
