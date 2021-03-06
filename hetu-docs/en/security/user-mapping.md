
Authentication User Mapping
===================

Authentication user mapping defines rules for mapping from users in the authentication system to openLooKeng users. This mapping is particularly important for Kerberos or LDAP authentication where the user names are complex like `alice@example` or `CN=Alice Smith, OU=Finance, O=Acme, C=US`.

Authentication user mapping can be configured with a simple regex extraction pattern, or more complex rules in a separate configuration file.

### Pattern Mapping Rule

The pattern mapping rule maps the authentication user to the first matching group in the regular expression. If the regular expression does not match the authentication user, authentication is denied.

Each authentication system has a separate property for the user mapping pattern to allow different mapping when multiple authentication systems are enabled:

| Authentication               | Property                                                      |
| ---------------------------- | ------------------------------------------------------------- |
| Username and Password (LDAP) | `http-server.authentication.password.user-mapping.pattern`    |
| Kerberos                     | `http-server.authentication.krb5.user-mapping.pattern`        |
| Certificate                  | `http-server.authentication.certificate.user-mapping.pattern` |
| Json Web Token               | `http-server.authentication.jwt.user-mapping.pattern`         |

### File Mapping Rules

The file mapping rules allow for more complex mappings from the authentication user. These rules are loaded from a JSON file defined in a configuration property. The mapping is based on the first matching rule, processed from top to bottom. If no rules match, authentication is denied. Each rule is composed of the following fields:

| Field Name     | Default Value | Required | Description                                                  |
| -------------- | ------------- | -------- | ------------------------------------------------------------ |
| pattern        | (none)        | Yes      | Regex to match against authentication user                   |
| user           | `$1`          | No       | Replacement string to substitute against pattern             |
| allow          | true          | No       | Boolean indicating if the authentication should be allowed   |

The following example maps all users like `alice@example.com` to just `alice`, except for the `test`user which is denied authentication, and it maps users like `bob@uk.example.com` to `bob_uk`:

``` json
{
    "rules": [
        {
            "pattern": "test@example\\.com",
            "allow": false
        },
        {
            "pattern": "(.+)@example\\.com"
        },
        {
            "pattern": "(?<user>.+)@(?<region>.+)\\.example\\.com",
            "user": "${user}_${region}"
        }
    ]
}
```

Each authentication system has a separate property for the user mapping file to allow different mapping when multiple authentication systems are enabled:

| Authentication               | Property                                                      |
| ---------------------------- | ------------------------------------------------------------- |
| Username and Password (LDAP) | `http-server.authentication.password.user-mapping.file`       |
| Kerberos                     | `http-server.authentication.krb5.user-mapping.file`           |
| Certificate                  | `http-server.authentication.certificate.user-mapping.file`    |
| Json Web Token               | `http-server.authentication.jwt.user-mapping.file`            |