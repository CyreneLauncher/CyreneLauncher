"""Minimal JVM class-file reader for ABI contracts (JVMS 4): members, flags, constants."""
import struct
from dataclasses import dataclass, field

ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED, ACC_STATIC = 0x0001, 0x0002, 0x0004, 0x0008
ACC_FINAL, ACC_NATIVE, ACC_INTERFACE, ACC_ABSTRACT = 0x0010, 0x0100, 0x0200, 0x0400


@dataclass
class Member:
    access: int
    name: str
    descriptor: str
    constant: object = None

    @property
    def exported(self):
        """Visible outside the package, so part of the binary contract."""
        return bool(self.access & (ACC_PUBLIC | ACC_PROTECTED))


@dataclass
class ClassFile:
    access: int
    name: str
    super_name: str
    interfaces: list
    fields: list = field(default_factory=list)
    methods: list = field(default_factory=list)

    def exported_members(self):
        """(kind, name, descriptor, static) for public/protected members, constructors included."""
        return {(kind, m.name, m.descriptor, bool(m.access & ACC_STATIC))
                for kind, members in (('field', self.fields), ('method', self.methods))
                for m in members if m.exported}


def _pool(data):
    """Read constant-pool entries without accepting truncated or dangling data."""
    if data[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('not a class file')
    if len(data) < 10:
        raise ValueError('truncated class header')
    count = struct.unpack_from('>H', data, 8)[0]
    if count == 0:
        raise ValueError('invalid constant pool count')
    pool, offset, index = {}, 10, 1
    while index < count:
        if offset >= len(data):
            raise ValueError('truncated constant pool')
        tag = data[offset]
        if tag == 1:
            if offset + 3 > len(data):
                raise ValueError('truncated UTF-8 constant')
            length = struct.unpack_from('>H', data, offset + 1)[0]
            if offset + 3 + length > len(data):
                raise ValueError('truncated UTF-8 constant')
            # Class files use modified UTF-8: NUL is encoded as C0 80.
            raw = data[offset + 3:offset + 3 + length].replace(b'\xc0\x80', b'\x00')
            try:
                pool[index] = raw.decode('utf-8', 'surrogatepass')
            except UnicodeDecodeError as exc:
                raise ValueError('invalid modified UTF-8 constant') from exc
            offset += 3 + length
        elif tag == 3:
            if offset + 5 > len(data):
                raise ValueError('truncated integer constant')
            pool[index] = struct.unpack_from('>i', data, offset + 1)[0]
            offset += 5
        elif tag == 4:
            if offset + 5 > len(data):
                raise ValueError('truncated float constant')
            pool[index] = struct.unpack_from('>f', data, offset + 1)[0]
            offset += 5
        elif tag == 5:
            if offset + 9 > len(data) or index + 1 >= count:
                raise ValueError('truncated long constant')
            pool[index] = struct.unpack_from('>q', data, offset + 1)[0]
            offset += 9
            index += 1
        elif tag == 6:
            if offset + 9 > len(data) or index + 1 >= count:
                raise ValueError('truncated double constant')
            pool[index] = struct.unpack_from('>d', data, offset + 1)[0]
            offset += 9
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            if offset + 3 > len(data):
                raise ValueError('truncated constant reference')
            pool[index] = ('ref', tag, struct.unpack_from('>H', data, offset + 1)[0])
            offset += 3
        elif tag == 15:
            if offset + 4 > len(data):
                raise ValueError('truncated method handle')
            pool[index] = ('handle', data[offset + 1], struct.unpack_from('>H', data, offset + 2)[0])
            offset += 4
        elif tag in (9, 10, 11, 12, 17, 18):
            if offset + 5 > len(data):
                raise ValueError('truncated member constant')
            pool[index] = (tag,) + struct.unpack_from('>HH', data, offset + 1)
            offset += 5
        else:
            raise ValueError(f'constant tag {tag}')
        index += 1
    return pool, offset


def parse(data):
    pool, offset = _pool(data)

    def utf8(entry):
        return pool[pool[entry][2]] if isinstance(pool[entry], tuple) else pool[entry]

    try:
        return _parse_body(data, pool, offset, utf8)
    except (IndexError, KeyError, struct.error) as exc:
        raise ValueError('malformed or truncated class file') from exc


def _parse_body(data, pool, offset, utf8):
    access, this_class, super_class, interface_count = struct.unpack_from('>HHHH', data, offset)
    offset += 8
    interfaces = [utf8(struct.unpack_from('>H', data, offset + 2 * i)[0]) for i in range(interface_count)]
    offset += 2 * interface_count
    result = ClassFile(access, utf8(this_class), utf8(super_class) if super_class else None, interfaces)

    for target in (result.fields, result.methods):
        member_count = struct.unpack_from('>H', data, offset)[0]
        offset += 2
        for _ in range(member_count):
            flags, name, descriptor, attributes = struct.unpack_from('>HHHH', data, offset)
            offset += 8
            member = Member(flags, pool[name], pool[descriptor])
            for _ in range(attributes):
                attribute, length = struct.unpack_from('>HI', data, offset)
                if offset + 6 + length > len(data):
                    raise ValueError('truncated member attribute')
                if pool[attribute] == 'ConstantValue':
                    value = pool[struct.unpack_from('>H', data, offset + 6)[0]]
                    member.constant = pool[value[2]] if isinstance(value, tuple) else value
                offset += 6 + length
            target.append(member)
    # Class attributes follow methods. Validate the complete remaining structure.
    attributes = struct.unpack_from('>H', data, offset)[0]
    offset += 2
    for _ in range(attributes):
        _, length = struct.unpack_from('>HI', data, offset)
        offset += 6 + length
        if offset > len(data):
            raise ValueError('truncated class attribute')
    if offset != len(data):
        raise ValueError('trailing class data')
    return result


def references(data: bytes) -> set[tuple[str, str, str, str]]:
    """Return exact field/method identities from Fieldref and Methodref constants."""
    pool, _ = _pool(data)
    parse(data)  # Also validate the body, which a pool-only scan would otherwise skip.

    def utf8(index):
        value = pool[index]
        if not isinstance(value, str):
            raise ValueError('expected UTF-8 constant')
        return value

    found = set()
    try:
        for entry in pool.values():
            if isinstance(entry, tuple) and entry[0] in (9, 10, 11):
                _, class_index, pair_index = entry
                owner = utf8(pool[class_index][2])
                pair = pool[pair_index]
                if pair[0] != 12:
                    raise ValueError('expected NameAndType constant')
                found.add(('field' if entry[0] == 9 else 'method', owner,
                           utf8(pair[1]), utf8(pair[2])))
    except (IndexError, KeyError, TypeError) as exc:
        raise ValueError('invalid member reference') from exc
    return found


def java_type(descriptor):
    """Field descriptor to Java source type; nested classes use '.'."""
    dimensions = len(descriptor) - len(descriptor.lstrip('['))
    base = descriptor[dimensions:]
    primitive = {'B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float', 'I': 'int', 'J': 'long',
                 'S': 'short', 'Z': 'boolean', 'V': 'void'}
    name = primitive[base] if base in primitive else base[1:-1].replace('/', '.').replace('$', '.')
    return name + '[]' * dimensions


def split_method(descriptor):
    """Method descriptor to ([parameter field descriptors], return field descriptor)."""
    parameters, index = [], 1
    while descriptor[index] != ')':
        start = index
        while descriptor[index] == '[':
            index += 1
        index = descriptor.index(';', index) + 1 if descriptor[index] == 'L' else index + 1
        parameters.append(descriptor[start:index])
    return parameters, descriptor[index + 1:]
