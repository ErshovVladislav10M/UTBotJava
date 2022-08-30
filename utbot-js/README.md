
![Logo](https://user-images.githubusercontent.com/70969943/187214891-9727eaac-17b2-436b-8b96-81e97266a78e.png)


# UTBot JavaScript

This is a module that provides JavaScript support in UTBot Java.

It is assumed that you have already read the [UTBot Java Readme](https://github.com/UnitTestBot/UTBotJava/blob/main/README.md).

## Requirements

* NodeJs 10.0.0 or higher (available to download https://nodejs.org/en/download/)

## Installation

Open the [newest action](https://github.com/UnitTestBot/UTBotJava/actions/workflows/publish-plugin-and-cli-from-branch.yml?query=branch%3Arudolf101%2Futbot-js-support+is%3Asuccess++)  and move on to the next step:

<details>
  <summary>Install UTBot plugin for IntelliJ IDEA</summary>

1. Download `utbot-intellij` plugin from the above link.
2. Open your IntelliJ IDEA `v2022.1`. If you don't have one, get it from [JetBrains](https://www.jetbrains.com/idea/download/#section=windows).
3. Install plugin following this [instruction](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk).

Now you can find the UTBot plugin enabled in the **File → Settings → Plugins** window.<br>Create JavaScript project and press ALT+SHIFT+U (or ALT+U, ALT+T in Linux).

____________
</details>

<details>
  <summary>Install UTBot Command Line Interface</summary>

1. Download `utbot-cli` from the above link and unzip it.
2. Follow instruction from [CLI documentation](https://github.com/UnitTestBot/UTBotJava/blob/rudolf101/utbot-js-support/utbot-js/docs/CLI.md).

Now you can use UTBot with CLI.

____________
</details>



## Peculiar properties

- Use `require` instead of `import` in your .js files
## Features

- Type inference of function arguments based on their usages 



## Roadmap

- Add symbolic execution
- JavaScript type annotations
- Creating own type guessing engine
- TypeScript support

## Documentation

[Command Line Interface](https://github.com/UnitTestBot/UTBotJava/blob/rudolf101/utbot-js-support/utbot-js/docs/CLI.md)


## Contributing

Contributions are always welcome!

See [contributing guide](https://github.com/UnitTestBot/UTBotJava/blob/main/CONTRIBUTING.md) for ways to get started.


## Support

Having troubles with using UTBot? Contact us [directly](https://www.utbot.org/contact/).


## Related

Here are some related projects

[UTBotCpp](https://github.com/UnitTestBot/UTBotCpp)

