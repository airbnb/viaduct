# Install selfhosted runner 

    sudo chown -R $(whoami) /opt/homebrew

    brew tap circleci-public/circleci

    brew install circleci-runner

### Add token to config.yaml and add runner name

    nano $HOME/Library/Preferences/com.circleci.runner/config.yaml

### Review and accept the Apple signature notarization

    spctl -a -vvv -t install "$(brew --prefix)/bin/circleci-runner"

    sudo xattr -r -d com.apple.quarantine "$(brew --prefix)/bin/circleci-runner"

### Start macOS machine runner 3

    sudo mv $HOME/Library/LaunchAgents/com.circleci.runner.plist /Library/LaunchAgents/

    launchctl bootstrap user/$(id -u) /Library/LaunchAgents/com.circleci.runner.plist
    launchctl enable user/$(id -u)/com.circleci.runner
    launchctl kickstart -k user/$(id -u)/com.circleci.runner

### Finally, you can check the service is running by invoking the following command:

    launchctl print user/$(id -u)/com.circleci.runner