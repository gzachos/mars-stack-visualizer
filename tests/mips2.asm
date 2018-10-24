
	.globl main
	.text

main:
	addi	$sp, $sp, -4
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -1
	addi	$sp, $sp, -2
	addi	$sp, $sp, -2
	addi	$sp, $sp, -2
	addi	$sp, $sp, -2
	addi	$sp, $sp, -3
	addi	$sp, $sp, -3
	addi	$sp, $sp, -3
	addi	$sp, $sp, -3

	li	$v0, 10
	syscall	
