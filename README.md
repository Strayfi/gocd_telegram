# gocd_telegram
GOCD notification plugin for telegram.
In the home directory of the go user (/var/go)
a file is created .telegram_settings 
api_token=
chat_id=
panic_chat_id=
gocd_api_url=http://myhost.com:8153/go
gocd_api_token=
(For the panic version) and
api_token=
chat_id=
(For the default version) 

The panic version differs in 2 things
1. views the state of the last instance via API
2. sends a panic message to a separate chat if the build failed, and the previous launch was successful

This plugin is a fork of this project https://github.com/cinex-ru/gocd-telegram-notifier (since in its current form it did not work on the latest build)
I would like to thank the author for the good code base.
