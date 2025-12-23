use crate::error::ApiError;
use crate::models::Script;
use bech32::{Bech32, Bech32m, Hrp};

const SECP256K1_CODE_HASH: &str =
    "9bd7e06f3ecf4be0f2fcd2188b23f1b9fcc88e5d4b65a8637b17723bbda3cce8";

pub fn decode_address(address: &str) -> Result<Script, ApiError> {
    // Try Bech32m first (CKB2021 format), then fall back to Bech32
    let (hrp, data, encoding) = match bech32::decode(address) {
        Ok((hrp, data)) => {
            // Determine encoding by trying to verify with each
            let is_bech32m = bech32::encode::<Bech32m>(hrp, &data)
                .map(|encoded| encoded.to_lowercase() == address.to_lowercase())
                .unwrap_or(false);
            let encoding = if is_bech32m { "bech32m" } else { "bech32" };
            (hrp, data, encoding)
        }
        Err(e) => {
            return Err(ApiError::InvalidAddress(format!(
                "Bech32 decode failed: {}",
                e
            )))
        }
    };

    // Validate network prefix
    let hrp_str = hrp.as_str();
    if hrp_str != "ckb" && hrp_str != "ckt" {
        return Err(ApiError::InvalidAddress(format!(
            "Invalid address prefix: {}",
            hrp_str
        )));
    }

    if data.is_empty() {
        return Err(ApiError::InvalidAddress("Empty payload".to_string()));
    }

    let format_type = data[0];

    match format_type {
        0x00 => {
            // CKB2021 full format (Bech32m): 0x00 + codeHash(32) + hashType(1) + args
            // This is the new standard format
            if data.len() < 34 {
                return Err(ApiError::InvalidAddress(format!(
                    "Invalid CKB2021 format length: {} (expected at least 34)",
                    data.len()
                )));
            }
            let code_hash = &data[1..33];
            let hash_type = match data[33] {
                0x00 => "data",
                0x01 => "type",
                0x02 => "data1",
                0x04 => "data2",
                _ => {
                    return Err(ApiError::InvalidAddress(format!(
                        "Invalid hash type byte: {}",
                        data[33]
                    )))
                }
            };
            let args = &data[34..];
            Ok(Script {
                code_hash: format!("0x{}", hex::encode(code_hash)),
                hash_type: hash_type.to_string(),
                args: format!("0x{}", hex::encode(args)),
            })
        }
        0x01 => {
            // Old short format (Bech32): 0x01 + codeHashIndex(1) + args
            if data.len() < 22 {
                return Err(ApiError::InvalidAddress(format!(
                    "Invalid short format length: {}",
                    data.len()
                )));
            }
            let code_hash_index = data[1];
            let args = &data[2..];

            let (code_hash, expected_args_len) = match code_hash_index {
                0x00 => (SECP256K1_CODE_HASH, 20), // secp256k1_blake160
                0x01 => (
                    "5c5069eb0857efc65e1bca0c07df34c31663b3622fd3876c876320fc9634e2a8",
                    20,
                ), // multisig
                _ => {
                    return Err(ApiError::InvalidAddress(format!(
                        "Unknown code hash index: {}",
                        code_hash_index
                    )))
                }
            };

            if args.len() != expected_args_len {
                return Err(ApiError::InvalidAddress(format!(
                    "Invalid args length for short format: {} (expected {})",
                    args.len(),
                    expected_args_len
                )));
            }

            Ok(Script {
                code_hash: format!("0x{}", code_hash),
                hash_type: "type".to_string(),
                args: format!("0x{}", hex::encode(args)),
            })
        }
        0x02 | 0x04 => {
            // Old full format (Bech32): 0x02/0x04 + codeHash(32) + args
            if data.len() < 33 {
                return Err(ApiError::InvalidAddress(format!(
                    "Invalid full format length: {}",
                    data.len()
                )));
            }
            let code_hash = &data[1..33];
            let hash_type = match format_type {
                0x02 => "data",
                0x04 => "type",
                _ => unreachable!(),
            };
            let args = &data[33..];
            Ok(Script {
                code_hash: format!("0x{}", hex::encode(code_hash)),
                hash_type: hash_type.to_string(),
                args: format!("0x{}", hex::encode(args)),
            })
        }
        _ => Err(ApiError::InvalidAddress(format!(
            "Unsupported address format: 0x{:02x}",
            format_type
        ))),
    }
}

pub fn encode_address(script: &Script, network: &str) -> Result<String, ApiError> {
    let hrp = Hrp::parse(match network {
        "mainnet" => "ckb",
        _ => "ckt",
    })
    .map_err(|e| ApiError::Internal(format!("Invalid HRP: {}", e)))?;

    let code_hash = script
        .code_hash
        .strip_prefix("0x")
        .unwrap_or(&script.code_hash);
    let args = script.args.strip_prefix("0x").unwrap_or(&script.args);

    let code_hash_bytes = hex::decode(code_hash)
        .map_err(|e| ApiError::InvalidScript(format!("Invalid code_hash hex: {}", e)))?;
    let args_bytes = hex::decode(args)
        .map_err(|e| ApiError::InvalidScript(format!("Invalid args hex: {}", e)))?;

    // Always use CKB2021 full format (Bech32m) for encoding
    // Format: 0x00 + codeHash(32) + hashType(1) + args
    let hash_type_byte = match script.hash_type.as_str() {
        "data" => 0x00u8,
        "type" => 0x01u8,
        "data1" => 0x02u8,
        "data2" => 0x04u8,
        _ => {
            return Err(ApiError::InvalidScript(format!(
                "Invalid hash type: {}",
                script.hash_type
            )))
        }
    };

    let mut payload = vec![0x00];
    payload.extend_from_slice(&code_hash_bytes);
    payload.push(hash_type_byte);
    payload.extend_from_slice(&args_bytes);

    bech32::encode::<Bech32m>(hrp, &payload)
        .map_err(|e| ApiError::Internal(format!("Bech32m encode failed: {}", e)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_decode_ckb2021_full_address() {
        // CKB2021 format address (Bech32m with 0x00 header)
        let address = "ckt1qzda0cr08m85hc8jlnfp3zer7xulejywt49kt2rr0vthywaa50xwsqg04e6twdm5wesxuxtwc96f35c4asf2h7gmnwlq0";
        let result = decode_address(address);
        assert!(result.is_ok(), "Failed to decode: {:?}", result.err());
        let script = result.unwrap();
        assert_eq!(script.hash_type, "type");
        assert_eq!(script.code_hash, format!("0x{}", SECP256K1_CODE_HASH));
    }

    #[test]
    fn test_decode_short_address() {
        // Old short format address (Bech32 with 0x01 header)
        let address = "ckt1qyqvsv5240xeh85wvnau2eky8pwrhh4jr8ts8vyj37";
        let result = decode_address(address);
        assert!(result.is_ok(), "Failed to decode: {:?}", result.err());
        let script = result.unwrap();
        assert_eq!(script.hash_type, "type");
    }

    #[test]
    fn test_encode_decode_roundtrip() {
        let script = Script {
            code_hash: format!("0x{}", SECP256K1_CODE_HASH),
            hash_type: "type".to_string(),
            args: "0x0fae74b7377476606e196ec17498d315ec12abf9".to_string(),
        };

        let encoded = encode_address(&script, "testnet").unwrap();
        let decoded = decode_address(&encoded).unwrap();

        assert_eq!(script.code_hash, decoded.code_hash);
        assert_eq!(script.hash_type, decoded.hash_type);
        assert_eq!(script.args, decoded.args);
    }
}
