//! Minimal DNS wire format: build an A query and check that a response answers it.

pub const RCODE_NOERROR: u8 = 0;
pub const RCODE_NXDOMAIN: u8 = 3;

const HEADER_LEN: usize = 12;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DnsError {
    InvalidName,
    TooShort,
    IdMismatch,
    NotResponse,
    QuestionMismatch,
    /// Server answered but with an RCODE that means it cannot resolve (SERVFAIL, REFUSED, ...).
    Rcode(u8),
}

/// Builds a recursive A/IN query for `name`.
pub fn build_query(id: u16, name: &str) -> Result<Vec<u8>, DnsError> {
    let mut q = Vec::with_capacity(HEADER_LEN + name.len() + 6);
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00]); // flags: RD
    q.extend_from_slice(&[0, 1, 0, 0, 0, 0, 0, 0]); // QDCOUNT=1
    encode_name(name, &mut q)?;
    q.extend_from_slice(&[0, 1, 0, 1]); // QTYPE=A, QCLASS=IN
    Ok(q)
}

fn encode_name(name: &str, out: &mut Vec<u8>) -> Result<(), DnsError> {
    let name = name.strip_suffix('.').unwrap_or(name);
    if name.is_empty() || name.len() > 253 {
        return Err(DnsError::InvalidName);
    }
    for label in name.split('.') {
        let valid = !label.is_empty()
            && label.len() <= 63
            && label
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_');
        if !valid {
            return Err(DnsError::InvalidName);
        }
        out.push(label.len() as u8);
        out.extend_from_slice(label.as_bytes());
    }
    out.push(0);
    Ok(())
}

/// Checks that `resp` is a reply to `query` from a working resolver.
/// NOERROR and NXDOMAIN count as healthy: the test domain may legitimately not exist,
/// and a blocked domain (Pi-hole) still answers NOERROR. Returns the RCODE.
pub fn validate_response(query: &[u8], resp: &[u8]) -> Result<u8, DnsError> {
    if resp.len() < HEADER_LEN {
        return Err(DnsError::TooShort);
    }
    if resp[0..2] != query[0..2] {
        return Err(DnsError::IdMismatch);
    }
    let is_response = resp[2] & 0x80 != 0;
    let opcode = (resp[2] >> 3) & 0x0F;
    if !is_response || opcode != 0 {
        return Err(DnsError::NotResponse);
    }
    // Question must be echoed. ASCII case folding is safe on the raw section:
    // label lengths (<64) and type/class bytes are never ASCII letters.
    let question = &query[HEADER_LEN..];
    let echoed = resp.get(HEADER_LEN..HEADER_LEN + question.len());
    if resp[4..6] != [0, 1] || !echoed.is_some_and(|e| e.eq_ignore_ascii_case(question)) {
        return Err(DnsError::QuestionMismatch);
    }
    match resp[3] & 0x0F {
        rc @ (RCODE_NOERROR | RCODE_NXDOMAIN) => Ok(rc),
        rc => Err(DnsError::Rcode(rc)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Turns a query into a response with the given RCODE (header + echoed question only).
    fn reply(query: &[u8], rcode: u8) -> Vec<u8> {
        let mut r = query.to_vec();
        r[2] |= 0x80;
        r[3] = 0x80 | rcode; // RA + rcode
        r
    }

    #[test]
    fn builds_expected_wire_format() {
        let q = build_query(0xABCD, "pi.hole.").unwrap();
        assert_eq!(
            q,
            [
                0xAB, 0xCD, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, //
                2, b'p', b'i', 4, b'h', b'o', b'l', b'e', 0, //
                0, 1, 0, 1
            ]
        );
    }

    #[test]
    fn rejects_bad_names() {
        for name in ["", ".", "a..b", "bad name.com", &"x".repeat(64)] {
            assert_eq!(build_query(1, name), Err(DnsError::InvalidName), "{name:?}");
        }
    }

    #[test]
    fn accepts_noerror_and_nxdomain() {
        let q = build_query(7, "example.com").unwrap();
        assert_eq!(validate_response(&q, &reply(&q, 0)), Ok(0));
        assert_eq!(validate_response(&q, &reply(&q, 3)), Ok(3));
    }

    #[test]
    fn accepts_case_randomized_echo() {
        let q = build_query(7, "example.com").unwrap();
        let r = reply(&q, 0).to_ascii_uppercase();
        // Uppercasing must not touch header/len bytes in this message.
        assert_eq!(validate_response(&q, &r), Ok(0));
    }

    #[test]
    fn rejects_invalid_responses() {
        let q = build_query(7, "example.com").unwrap();
        let ok = reply(&q, 0);

        assert_eq!(validate_response(&q, &ok[..5]), Err(DnsError::TooShort));

        let mut r = ok.clone();
        r[1] ^= 1;
        assert_eq!(validate_response(&q, &r), Err(DnsError::IdMismatch));

        assert_eq!(validate_response(&q, &q), Err(DnsError::NotResponse));

        let mut r = ok.clone();
        r[13] = b'z';
        assert_eq!(validate_response(&q, &r), Err(DnsError::QuestionMismatch));

        assert_eq!(
            validate_response(&q, &ok[..14]),
            Err(DnsError::QuestionMismatch)
        );

        assert_eq!(
            validate_response(&q, &reply(&q, 2)),
            Err(DnsError::Rcode(2))
        ); // SERVFAIL
        assert_eq!(
            validate_response(&q, &reply(&q, 5)),
            Err(DnsError::Rcode(5))
        ); // REFUSED
    }
}
