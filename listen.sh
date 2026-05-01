#!/bin/bash
stripe listen --forward-to http://zenshin:8000/okaikei/webhook/stripe/ --skip-verify
