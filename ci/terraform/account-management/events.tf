data "aws_sns_topic" "events" {
  name = "${var.environment}-events"
}

locals {
  audit_signing_key_alias_name = data.terraform_remote_state.shared.outputs.audit_signing_key_alias_name
}