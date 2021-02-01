	.globl main
	.text
main:
#	li	$sp, 0x7ffffffc
	li	$sp, 0x7fffffff
	addi	$sp, $sp, -3
	# Byte-length ops
	li	$t1, 0xef
	sb	$t1, 0($sp)
	sb	$t1, 1($sp)
	sb	$t1, 2($sp)
	sb	$t1, 3($sp)
	# Halfword-length ops
	li	$t1, 0xabcd
	sh	$t1, 0($sp)
	sh	$t1, 2($sp)
	# Word-length ops
	li	$t1, 0x12345678
	sw	$t1, 0($sp)
	
	# Byte-length ops (no offset)
	li	$t1, 0xef
	sb	$t1, 0($sp)
	addiu	$sp, $sp, 1
	sb	$t1, 0($sp)
	addiu	$sp, $sp, 1
	sb	$t1, 0($sp)
	addiu	$sp, $sp, 1
	sb	$t1, 0($sp)
	# Halfword-length ops
	addiu	$sp, $sp, -3
	li	$t1, 0xabcd
	sh	$t1, 0($sp)
	addiu	$sp, $sp, 2
	sh	$t1, 0($sp)
	
	li	$v0, 10
	syscall
