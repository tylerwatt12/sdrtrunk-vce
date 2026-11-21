'use strict';

function createEncryptionKeySnapshot(keys = null, revision = 'a'.repeat(64)) {
  const algorithm = (id, label, bytes, minimum = bytes) => ({ id, label, key_bytes: bytes,
    minimum_key_bytes: minimum, maximum_key_bytes: bytes });
  return { revision, protocols: [
    { id: 'APCO25', label: 'P25', algorithms: [algorithm(129, 'DES-OFB', 8),
      algorithm(132, 'AES-256', 32), algorithm(170, 'ADP', 5, 1)] },
    { id: 'DMR', label: 'DMR', algorithms: [algorithm(33, 'DMRA RC4/EP', 5),
      algorithm(36, 'DMRA AES-128', 16), algorithm(37, 'DMRA AES-256', 32)] },
    { id: 'NXDN', label: 'NXDN', algorithms: [algorithm(1, 'Scrambler', 2),
      algorithm(2, 'DES-OFB', 8), algorithm(3, 'AES-256-OFB', 32)] }
  ], keys: keys || [
    { id: '11111111-1111-4111-8111-111111111111', label: 'County Dispatch', enabled: true,
      protocol: 'APCO25', algorithm_id: 129, key_id: 42, key_hex: '0123456789ABCDEF', scope: 'County Public Safety' },
    { id: '22222222-2222-4222-8222-222222222222', label: 'DMR Tactical', enabled: false,
      protocol: 'DMR', algorithm_id: 33, key_id: 7, key_hex: '0102030405', scope: null },
    { id: '33333333-3333-4333-8333-333333333333', label: 'NXDN Maintenance', enabled: true,
      protocol: 'NXDN', algorithm_id: 1, key_id: 5, key_hex: '1234', scope: 'Maintenance' }
  ] };
}

module.exports = { createEncryptionKeySnapshot };
