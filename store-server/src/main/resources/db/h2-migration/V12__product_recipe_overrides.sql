-- Sparse overrides preserve shared recipe inheritance and historical drafts.
CREATE TABLE public.burger_item_component_override (
 burger_id INT NOT NULL REFERENCES public.menu_item(id),
 component_id INT NOT NULL REFERENCES public.burger_component(component_id),
 excluded BOOLEAN NOT NULL DEFAULT FALSE,
 is_removable BOOLEAN,
 sort_order INT,
 PRIMARY KEY(burger_id,component_id)
);
