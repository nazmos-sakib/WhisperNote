# Publish the website

1. Push this `docs` directory to the repository's `main` branch.
2. In GitHub, open **Settings → Pages**.
3. Select **Deploy from a branch**, **main**, and **/docs**, then Save.
4. Wait for the Pages deployment to finish.

Home: https://nazmos-sakib.github.io/WhisperNote/
Privacy: https://nazmos-sakib.github.io/WhisperNote/privacy/

Use the privacy URL in Play Console after verifying that it loads publicly. No custom domain or manually authored Actions workflow is required. Do not add `.nojekyll`: this site uses Jekyll.

The shared layout is `_layouts/default.html`. Cayman is the base theme, with local CSS customization. Screenshots are genuine demonstration-build captures from earlier app verification and contain sample text. Replace them with newer screenshots under `assets/screenshots` when the interface changes.

The maintainer's supplied privacy email is public on both pages. Keep the policy consistent with SDK behavior and the app's Privacy screen. Review it when releasing changes to data handling; hosting a policy does not by itself complete Play's Data safety form or third-party license audit.

Local preview with Ruby and Bundler:

```sh
cd docs
bundle install
bundle exec jekyll serve --baseurl /WhisperNote
```

Open http://localhost:4000/WhisperNote/. Do not commit `_site`, `.jekyll-cache`, `.sass-cache`, or local dependency directories.
