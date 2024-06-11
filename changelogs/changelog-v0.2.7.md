Changes:

* Improved `setupJavadoc`.
  * Made it automatically apply `-Werror` by default.
  * Made `javadoc-options.txt` file optional.
  * Made `javadoc_package_name` property optional.
* Added `javadoc-links.txt` file which can optionally be used to specify extra javadoc links.
* Added automatically added `--link-modularity-mismatch` option, as some links are modular.
* Added link filtering to detect and ignore dead javadoc links.
