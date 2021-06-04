module "api-gateway-root" {
  source = "../modules/api-gateway-root"
  providers = {
    aws = aws.localstack
  }
  environment = var.environment
}

module "userinfo" {
  source = "../modules/endpoint-module"
  providers = {
    aws = aws.localstack
  }

  endpoint-name   = "userinfo"
  endpoint-method = "GET"
  handler-environment-variables = {
    BASE_URL = var.api_base_url
  }
  handler-function-name = "uk.gov.di.lambdas.UserInfoHandler::handleRequest"

  rest_api_id               = module.api-gateway-root.di_authentication_api_id
  root_resource_id          = module.api-gateway-root.root_resource_id
  execution_arn             = module.api-gateway-root.execution_arn
  api-deployment-stage-name = var.api_deployment_stage_name
  lambda-zip-file           = var.lambda-zip-file
}

module "token" {
  source = "../modules/token"
  providers = {
    aws = aws.localstack
  }
  rest_api_id               = module.api-gateway-root.di_authentication_api_id
  root_resource_id          = module.api-gateway-root.root_resource_id
  execution_arn             = module.api-gateway-root.execution_arn
  api-deployment-stage-name = var.api_deployment_stage_name
  lambda-zip-file           = var.lambda-zip-file
}

module "authorize" {
  source = "../modules/endpoint-module"
  providers = {
    aws = aws.localstack
  }

  endpoint-name   = "authorize"
  endpoint-method = "GET"
  handler-environment-variables = {
    BASE_URL = var.api_base_url
  }
  handler-function-name = "uk.gov.di.lambdas.AuthorisationHandler::handleRequest"

  rest_api_id               = module.api-gateway-root.di_authentication_api_id
  root_resource_id          = module.api-gateway-root.root_resource_id
  execution_arn             = module.api-gateway-root.execution_arn
  api-deployment-stage-name = var.api_deployment_stage_name
  lambda-zip-file           = var.lambda-zip-file
}

module "wellknown" {
  source = "../modules/wellknown"
  providers = {
    aws = aws.localstack
  }
  rest_api_id               = module.api-gateway-root.di_authentication_api_id
  root_resource_id          = module.api-gateway-root.wellknown_resource_id
  execution_arn             = module.api-gateway-root.execution_arn
  api-deployment-stage-name = var.api_deployment_stage_name
  lambda-zip-file           = var.lambda-zip-file
}
