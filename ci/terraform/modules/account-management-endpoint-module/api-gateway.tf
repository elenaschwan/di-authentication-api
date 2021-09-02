resource "aws_api_gateway_resource" "endpoint_resource" {
  rest_api_id = var.rest_api_id
  parent_id   = var.root_resource_id
  path_part   = var.path_part
}

resource "aws_api_gateway_method" "endpoint_method" {
  rest_api_id   = var.rest_api_id
  resource_id   = aws_api_gateway_resource.endpoint_resource.id
  http_method   = var.endpoint_method
  request_parameters   = var.method_request_parameters
  authorizer_id = var.authorizer_id
  authorization = "CUSTOM"

  depends_on = [
    aws_api_gateway_resource.endpoint_resource
  ]
}

resource "aws_api_gateway_integration" "endpoint_integration" {
  rest_api_id          = var.rest_api_id
  resource_id          = aws_api_gateway_resource.endpoint_resource.id
  http_method          = aws_api_gateway_method.endpoint_method.http_method
  request_parameters   = var.integration_request_parameters

  integration_http_method = "POST"
  type                    = "AWS_PROXY"
  uri                     = aws_lambda_alias.endpoint_lambda.invoke_arn

  depends_on = [
    aws_api_gateway_resource.endpoint_resource,
    aws_api_gateway_method.endpoint_method,
    aws_lambda_function.endpoint_lambda,
  ]
}

resource "aws_lambda_permission" "endpoint_execution_permission" {
  statement_id  = "AllowAPIGatewayInvoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.endpoint_lambda.function_name
  principal     = "apigateway.amazonaws.com"
  qualifier     = aws_lambda_alias.endpoint_lambda.name

  # The "/*/*" portion grants access from any method on any resource
  # within the API Gateway REST API.
  source_arn = "${var.execution_arn}/*/*"

  depends_on = [
    aws_lambda_function.endpoint_lambda
  ]
}