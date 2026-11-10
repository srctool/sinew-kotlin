# Module sinew-devtools-noop

The stand-in that production builds depend on instead of `sinew-devtools` and `sinew-devtools-ui`.

It declares exactly the public API of those two modules, in the same packages, with nothing behind it: nothing is
recorded, no fault rule applies, no host is overridden, `DevTools.httpHooks` is `SinewHttpHooks.None`, `SinewDevTools`
only calls its content, and `DevToolsMenuItem` draws nothing. Where a value has to come back it is the safe one:
every name counts as sensitive, and no body is kept.

Its API is checked against the real modules' in CI (`checkDevtoolsNoopAbi`), so the API reference of `sinew-devtools`
and `sinew-devtools-ui` is the reference for this module too. Only what a declaration *does* differs, and that is
nothing.
