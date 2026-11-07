package com.nullandkale.nullspace.ui.guide;

import com.nullandkale.nullspace.content.guide.GuideRecipes;

/** Metadata belongs to this book access, never to a global current-book slot. */
public interface RecipeBookAccess {
  GuideRecipes nullspace$recipes();

  void nullspace$recipes(GuideRecipes recipes);
}
