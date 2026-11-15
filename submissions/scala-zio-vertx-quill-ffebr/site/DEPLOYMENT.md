# GitHub Pages deployment

The `Conduit JDBC and Vert.x` workflow builds and deploys this report to GitHub Pages after its `verify` job succeeds. Deployment runs only for pushes to `main` and manual runs on `main` in `ffebr/vertx-jdbc-challenge`. It builds the checked out commit and publishes the generated `site/_build` directory.

## Repository setup

An administrator must open [Settings → Pages](https://github.com/ffebr/vertx-jdbc-challenge/settings/pages), enable GitHub Pages, and select **GitHub Actions** as the build and deployment source. The workflow requests `pages: write` and `id-token: write`, and uses the `github-pages` environment. Configure that environment's deployment branch restriction for `main` if environment restrictions are used.

The workflow does not ask `actions/configure-pages` to enable Pages through the API. Enabling Pages is a repository administration operation; the workflow's `GITHUB_TOKEN` is not granted repository administration rights. This keeps Pages setup explicit and avoids granting an admin-capable token to CI.

The workflow builds, checks, and uploads the site artifact before configuring and deploying Pages. If Pages has not been enabled yet, the job can fail at configuration while leaving the checked artifact available on that run for review.

## Local preview

From the submission directory (`submissions/scala-zio-vertx-quill-ffebr`):

```sh
python3 site/build.py --output site/_preview
python3 site/check.py site/_preview
python3 -m http.server 4173 --bind 127.0.0.1 --directory site/_preview
```

Open `http://127.0.0.1:4173`. Without a validation URL, the page is labelled a
local draft. The builder records the current Git commit, extracts code from the
actual Scala sources, and rejects a supplied SHA that differs from the checkout.
CI supplies the exact checkout SHA and its own GitHub Actions validation URL.
Generated `_preview` and `_build` directories are ignored by Git.

After setup, the site is available at the Pages URL reported by the deployment job. The job's validation link points to the GitHub Actions run that built the deployed revision.
