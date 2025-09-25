# Environment Variables

The following environment variables must be set to enable Slack error notifications:

- `CIRCLE_TOKEN` → Generate in **CircleCI > User Settings > Personal API Tokens**  
- `SLACK_ACCESS_TOKEN` → OAuth token with permission to post messages (Bot User OAuth Token) > https://api.slack.com/apps/ > Select Slack app > OAuth & Permissions in Slack
- `SLACK_DEFAULT_CHANNEL` → Slack channel ID for notifications  

Add these variables in your CI/CD environment **before running the pipeline**.  

More details: [Slack Orb Setup Guide](https://github.com/CircleCI-Public/slack-orb/wiki/Setup)
